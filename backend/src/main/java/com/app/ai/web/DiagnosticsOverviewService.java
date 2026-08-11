package com.app.ai.web;

import com.app.ai.breaker.BreakerKey;
import com.app.ai.breaker.BreakerSnapshot;
import com.app.ai.breaker.ModelBreakerRegistry;
import com.app.ai.diagnostic.BreakerState;
import com.app.ai.diagnostic.DiagnosticRecorder;
import com.app.ai.model.Capability;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderConfigurationStatus;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 组装 api.md §5 的调度总览：配置快照来自 catalog，当前熔断状态来自 JVM registry，
 * 最近 24 小时聚合来自 {@code ai_logical_call}，真实验证过的能力来自 {@code ai_attempt}
 * 与它的父 {@code ai_logical_call}。
 *
 * <p>本服务只读，任何数据源读不到都退化成空值而不是抛错——无 Provider、缺 secret、
 * 配置禁用、诊断库不可达都要返回稳定对象，不能变成 500。
 */
@Service
public class DiagnosticsOverviewService {

    private static final Logger log = LoggerFactory.getLogger(DiagnosticsOverviewService.class);

    private static final String COUNTS_24H = """
            select
                count(*) filter (where failover_count > 0) as failovers,
                count(*) filter (where outcome in ('FAILED', 'STREAM_FAILED')) as failed_or_interrupted
            from ai_logical_call
            where started_at >= ?
            """;

    private static final String OLDEST_STARTED_AT = "select min(started_at) from ai_logical_call";

    /**
     * 真实验证过的 deployment-capability 单元。
     *
     * <p>判据落在 attempt 上而不是 logical call 上：{@code ai_attempt.outcome = 'SUCCEEDED'}
     * 才表示"这一次真的把请求打到了这个 deployment 并拿回了合法响应"。能力名只能从父
     * {@code ai_logical_call} 取——{@code ai_attempt} 没有 capability 列。
     *
     * <p>之所以不读 {@code ai_logical_call.actual_deployment_id}：约束
     * {@code ck_ai_call_execution_summary} 规定它只在 {@code contributing_deployment_count = 1}
     * 时有值，一次跨 deployment 的 mixed execution Embedding 即使整通成功，那三个
     * {@code actual_*} 列也全是 null，用它统计会把这些成功调用整通漏掉。反过来，一通
     * 最终成功的调用里那些 FAILED 的 failover attempt 也不该记功，attempt 级判据同时解决了这两面。
     *
     * <p>不能用 {@code outcome not in ('FAILED', 'STREAM_FAILED')}（总览里两个 24 小时聚合的
     * 口径）来反推成功：{@code RATE_LIMITED / TIMED_OUT / CANCELED / EMPTY_STREAM} 都不是失败
     * 也都不是"调通了"。同理 logical call 的 {@code DEGRADED} 在 RERANK 上恰恰意味着
     * {@code rerank_applied = false}，一次都没真调。
     */
    private static final String VERIFIED_CAPABILITIES = """
            select distinct call.capability, attempt.provider_id, attempt.deployment_id
            from ai_attempt attempt
            join ai_logical_call call on call.id = attempt.logical_call_id
            where attempt.outcome = 'SUCCEEDED'
            """;

    private final ModelCatalogSnapshot catalog;
    private final ModelBreakerRegistry breakers;
    private final DiagnosticRecorder recorder;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int maxAgeDays;
    private final int maxLogicalCalls;

    @Autowired
    DiagnosticsOverviewService(
            ModelCatalogSnapshot catalog,
            ModelBreakerRegistry breakers,
            DiagnosticRecorder recorder,
            JdbcTemplate jdbc,
            @Value("${app.ai.diagnostics.retention.max-age-days:7}") int maxAgeDays,
            @Value("${app.ai.diagnostics.retention.max-logical-calls:10000}") int maxLogicalCalls) {
        this(catalog, breakers, recorder, jdbc, Clock.systemUTC(), maxAgeDays, maxLogicalCalls);
    }

    /** {@code jdbc} 传 null 表示诊断库不可读，此时聚合字段返回 null 而不是 0。 */
    public DiagnosticsOverviewService(
            ModelCatalogSnapshot catalog,
            ModelBreakerRegistry breakers,
            DiagnosticRecorder recorder,
            JdbcTemplate jdbc,
            Clock clock,
            int maxAgeDays,
            int maxLogicalCalls) {
        this.catalog = Objects.requireNonNull(catalog);
        this.breakers = Objects.requireNonNull(breakers);
        this.recorder = Objects.requireNonNull(recorder);
        this.jdbc = jdbc;
        this.clock = Objects.requireNonNull(clock);
        this.maxAgeDays = maxAgeDays;
        this.maxLogicalCalls = maxLogicalCalls;
    }

    public AiDiagnosticsOverview overview() {
        List<BreakerSnapshot> snapshots = breakers.snapshots();
        Aggregates aggregates = aggregates();
        return new AiDiagnosticsOverview(
                OffsetDateTime.now(clock),
                catalog.policyVersion(),
                catalog.configFingerprint(),
                new AiDiagnosticsOverview.Retention(
                        maxAgeDays, maxLogicalCalls, aggregates.oldestAvailableAt()),
                summary(snapshots, aggregates),
                deployments(),
                breakers(snapshots));
    }

    private AiDiagnosticsOverview.Summary summary(
            List<BreakerSnapshot> snapshots, Aggregates aggregates) {
        int openBreakers = (int) snapshots.stream()
                .filter(snapshot -> snapshot.state() == BreakerState.OPEN)
                .count();
        return new AiDiagnosticsOverview.Summary(
                eligibleCandidates(snapshots),
                openBreakers,
                aggregates.failovers24h(),
                aggregates.failedOrInterrupted24h(),
                readOrNull("诊断丢弃计数", recorder::droppedCount),
                readOrNull("诊断写失败计数", recorder::writeFailureCount));
    }

    /**
     * 口径是"enabled + CONFIGURED 且对应 capability 的 breaker 允许调用"的 deployment-capability
     * 单元数。registry 里没有条目说明该单元还没被调用过，等价于 CLOSED——这里不能用
     * {@code snapshot(target, capability)} 探测，那会凭空建出条目污染 breakers 列表。
     */
    private int eligibleCandidates(List<BreakerSnapshot> snapshots) {
        Map<BreakerKey, Boolean> allowsCall = new HashMap<>();
        for (BreakerSnapshot snapshot : snapshots) {
            allowsCall.put(snapshot.key(), snapshot.allowsCall());
        }
        int eligible = 0;
        for (ModelDeployment deployment : catalog.deployments().values()) {
            if (!available(deployment)) {
                continue;
            }
            for (Capability capability : deployment.capabilities()) {
                BreakerKey key =
                        new BreakerKey(deployment.providerId(), deployment.id(), capability);
                if (allowsCall.getOrDefault(key, true)) {
                    eligible++;
                }
            }
        }
        return eligible;
    }

    private boolean available(ModelDeployment deployment) {
        ModelProvider provider = catalog.providers().get(deployment.providerId());
        return deployment.enabled()
                && provider != null
                && provider.configurationStatus() == ProviderConfigurationStatus.CONFIGURED;
    }

    private List<AiDiagnosticsOverview.Deployment> deployments() {
        Map<DeploymentKey, Set<Capability>> verified = verifiedCapabilities();
        List<AiDiagnosticsOverview.Deployment> result = new ArrayList<>();
        for (ModelDeployment deployment : catalog.deployments().values()) {
            ModelProvider provider = catalog.providers().get(deployment.providerId());
            DeploymentKey key =
                    new DeploymentKey(deployment.providerId().value(), deployment.id().value());
            result.add(new AiDiagnosticsOverview.Deployment(
                    deployment.providerId().value(),
                    provider == null ? null : provider.displayName(),
                    deployment.id().value(),
                    deployment.modelIdentity().value(),
                    deployment.capabilities().stream().sorted().toList(),
                    deployment.tiers().stream().sorted().toList(),
                    provider == null ? null : provider.dataRegion().value(),
                    deployment.enabled(),
                    provider == null ? null : provider.configurationStatus().name(),
                    verified.getOrDefault(key, Set.of()).stream().sorted().toList()));
        }
        return result;
    }

    /**
     * 按 {@link #VERIFIED_CAPABILITIES} 归集"真的调通过"的能力，不与 catalog 的
     * {@code capabilities} 做任何交集或兜底——那份是 adapter 理论能力，"这个部署理论上支持
     * RERANK"和"我们真的成功调通过它的 RERANK"是两件事，用前者顶替后者会让准入结论凭空成立。
     *
     * <p>读不到时返回空 map，也就是页面显示"尚未验证"。这个方向是刻意选的：总览里的计数用 null
     * 表达"读不到"，因为 0 会被读成"一切正常"；而验证记录是准入证据，缺证据时少声称一项能力
     * 是安全的，多声称才是危险的，所以它 fail closed 而不是 fail open。
     *
     * <p>范围仅限保留窗口内的诊断记录（ADR 0005：7 天或最近 10,000 次逻辑调用后物理删除，
     * 子表随 {@code on delete cascade} 一起没）。因此这个字段的准确语义是"在现存诊断记录里
     * 调通过"，长期不调用的能力会自然掉出来。
     */
    private Map<DeploymentKey, Set<Capability>> verifiedCapabilities() {
        if (jdbc == null) {
            return Map.of();
        }
        List<Map.Entry<DeploymentKey, Capability>> rows = readOrNull(
                "真实验证过的能力",
                () -> jdbc.query(VERIFIED_CAPABILITIES, (resultSet, rowNum) -> Map.entry(
                        new DeploymentKey(
                                resultSet.getString("provider_id"),
                                resultSet.getString("deployment_id")),
                        Capability.valueOf(resultSet.getString("capability")))));
        if (rows == null) {
            return Map.of();
        }
        Map<DeploymentKey, Set<Capability>> verified = new HashMap<>();
        for (Map.Entry<DeploymentKey, Capability> row : rows) {
            verified.computeIfAbsent(row.getKey(), absent -> EnumSet.noneOf(Capability.class))
                    .add(row.getValue());
        }
        return verified;
    }

    private List<AiDiagnosticsOverview.Breaker> breakers(List<BreakerSnapshot> snapshots) {
        return snapshots.stream()
                .map(snapshot -> new AiDiagnosticsOverview.Breaker(
                        snapshot.key().value(),
                        snapshot.target().providerId().value(),
                        snapshot.target().deploymentId().value(),
                        snapshot.target().modelIdentity().value(),
                        snapshot.key().capability(),
                        snapshot.state().name(),
                        snapshot.allowsCall(),
                        snapshot.halfOpenPermissionInUse(),
                        utc(snapshot.lastChangedAt()),
                        snapshot.lastChangeReason() == null
                                ? null
                                : snapshot.lastChangeReason().name(),
                        new AiDiagnosticsOverview.Recent(
                                snapshot.successfulCalls(),
                                snapshot.failedCalls(),
                                snapshot.rateLimitedCalls(),
                                snapshot.slowCalls())))
                .toList();
    }

    private Aggregates aggregates() {
        if (jdbc == null) {
            return Aggregates.UNAVAILABLE;
        }
        OffsetDateTime since = OffsetDateTime.now(clock).minusHours(24);
        Counts counts = readOrNull("最近 24 小时聚合", () -> jdbc.queryForObject(
                COUNTS_24H,
                (rs, rowNum) -> new Counts(rs.getLong(1), rs.getLong(2)),
                since));
        OffsetDateTime oldest = readOrNull("最早诊断记录时间",
                () -> jdbc.queryForObject(OLDEST_STARTED_AT, OffsetDateTime.class));
        return new Aggregates(
                counts == null ? null : counts.failovers(),
                counts == null ? null : counts.failedOrInterrupted(),
                utc(oldest));
    }

    /** 读取失败一律返回 null：把故障伪装成 0 会让页面显示"一切正常"。 */
    private static <T> T readOrNull(String what, Supplier<T> read) {
        try {
            return read.get();
        } catch (RuntimeException failure) {
            log.warn("调度总览读取{}失败，本次返回空值而不是 0", what, failure);
            return null;
        }
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private record Counts(long failovers, long failedOrInterrupted) {
    }

    /**
     * 验证记录按 (provider, deployment) 两元组归集，不只按 deployment id：deployment id 被挪到
     * 另一个 Provider 名下之后，旧 Provider 上的成功记录不该继续替新组合作证。
     */
    private record DeploymentKey(String providerId, String deploymentId) {
    }

    private record Aggregates(
            Long failovers24h, Long failedOrInterrupted24h, OffsetDateTime oldestAvailableAt) {

        private static final Aggregates UNAVAILABLE = new Aggregates(null, null, null);
    }
}
