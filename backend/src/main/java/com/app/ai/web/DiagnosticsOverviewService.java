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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 组装 api.md §5 的调度总览：配置快照来自 catalog，当前熔断状态来自 JVM registry，
 * 最近 24 小时聚合来自 {@code ai_logical_call}。
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
        List<AiDiagnosticsOverview.Deployment> result = new ArrayList<>();
        for (ModelDeployment deployment : catalog.deployments().values()) {
            ModelProvider provider = catalog.providers().get(deployment.providerId());
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
                    List.of()));
        }
        return result;
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

    private record Aggregates(
            Long failovers24h, Long failedOrInterrupted24h, OffsetDateTime oldestAvailableAt) {

        private static final Aggregates UNAVAILABLE = new Aggregates(null, null, null);
    }
}
