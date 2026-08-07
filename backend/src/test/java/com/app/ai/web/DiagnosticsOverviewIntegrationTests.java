package com.app.ai.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.breaker.Resilience4jModelBreakerRegistry;
import com.app.ai.diagnostic.AttemptDiagnostic;
import com.app.ai.diagnostic.AttemptKind;
import com.app.ai.diagnostic.AttemptOutcome;
import com.app.ai.diagnostic.BreakerState;
import com.app.ai.diagnostic.CallOutcome;
import com.app.ai.diagnostic.CompletedCallDiagnostic;
import com.app.ai.diagnostic.DiagnosticRecorder;
import com.app.ai.diagnostic.JdbcDiagnosticStore;
import com.app.ai.diagnostic.RouteCandidateDiagnostic;
import com.app.ai.diagnostic.RouteDecision;
import com.app.ai.diagnostic.RouteEventDiagnostic;
import com.app.ai.diagnostic.RouteEventType;
import com.app.ai.diagnostic.TimingDiagnostic;
import com.app.ai.diagnostic.UsageDiagnostic;
import com.app.ai.model.AiRuntimePolicy;
import com.app.ai.model.Capability;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ProviderId;
import com.app.ai.model.QuotaDomain;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.TerminationReason;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 总览里两条 {@code ai_logical_call} 查询的真库验证。
 *
 * <p>单元测试全程传 {@code jdbc = null}，只覆盖"读不到"分支；聚合口径本身
 * （数调用数而不是把切换次数求和、窗口边界、最早记录取全表）只能对着真实 Schema 验。
 * 记录一律走真实的 {@link JdbcDiagnosticStore} 写入，这样 {@code failover_count}
 * 是由 FAILOVER attempt 推导出来的真实值，而不是测试自己拍进去的数字。
 */
class DiagnosticsOverviewIntegrationTests {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-03T12:00:00Z");
    private static final QuotaDomain QUOTA = new QuotaDomain("quota-a");
    private static final ExecutionTarget TARGET_A = target("chat-a");
    private static final ExecutionTarget TARGET_B = target("chat-b");
    private static final ExecutionTarget TARGET_C = target("chat-c");

    private final String baseUrl = withoutQuery(System.getProperty(
            "spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/app"));
    private final String username = System.getProperty("spring.datasource.username", "app");
    private final String password = System.getProperty("spring.datasource.password", "app");

    private String schema;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createSchema() throws SQLException {
        schema = "xrag_diag_overview_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        dataSource = new DriverManagerDataSource(schemaUrl(), username, password);
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterEach
    void dropSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void 两个24小时聚合按调用数统计且窗口外的记录不计入() {
        var store = new JdbcDiagnosticStore(jdbc, new DataSourceTransactionManager(dataSource));
        // 窗口内：一次切换两次后成功，一次流式中断且没有 failover。
        store.write(succeededAfterTwoFailovers(callId(1), NOW.minusHours(1)));
        store.write(streamFailedWithoutFailover(callId(2), NOW.minusHours(2)));
        // 窗口外：两次 failover 且最终失败，两个聚合都不该看见它。
        store.write(failedAfterTwoFailovers(callId(3), NOW.minusHours(30)));

        AiDiagnosticsOverview overview = service().overview();

        // failovers24h 数的是"发生过 failover 的调用数"，不是把切换次数求和：窗口内
        // 只有 callId(1) 一条，但它自己切换了 2 次——求和会得到 2，计数才得到 1。
        assertThat(overview.summary().failovers24h()).isEqualTo(1L);
        assertThat(overview.summary().failedOrInterrupted24h()).isEqualTo(1L);
        // oldestAvailableAt 取全表最早，不受 24 小时窗口限制。
        assertThat(overview.retention().oldestAvailableAt())
                .isEqualTo(NOW.minusHours(30).withOffsetSameInstant(ZoneOffset.UTC));
    }

    @Test
    void 空库返回零与空最早时间而不是把它们当成读取失败() {
        AiDiagnosticsOverview overview = service().overview();

        // 与"读不到"必须区分开：确实没有记录时聚合是 0，只有查询失败才返回 null。
        assertThat(overview.summary().failovers24h()).isZero();
        assertThat(overview.summary().failedOrInterrupted24h()).isZero();
        assertThat(overview.retention().oldestAvailableAt()).isNull();
    }

    private DiagnosticsOverviewService service() {
        var catalog = new ModelCatalogSnapshot(
                "test-v1", "a".repeat(64), Map.of(), Map.of(), runtimePolicy());
        return new DiagnosticsOverviewService(
                catalog,
                new Resilience4jModelBreakerRegistry(
                        catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true),
                new CountingRecorder(),
                jdbc,
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC),
                7,
                10_000);
    }

    /** 刻意切换两次：只有这样 {@code count(*) filter} 才和 {@code sum(failover_count)} 区分得开。 */
    private static CompletedCallDiagnostic succeededAfterTwoFailovers(
            UUID callId, OffsetDateTime startedAt) {
        return new CompletedCallDiagnostic(
                callId, "request-" + callId, Capability.CHAT, ServiceTier.BALANCED, null, false,
                "test-v1", "a".repeat(64),
                CallOutcome.SUCCEEDED, null, null, TerminationReason.COMPLETED,
                null, ChatFinishReason.STOP, true, null, null,
                new UsageDiagnostic(2, 1, null, null, null, null, null, null),
                timing(startedAt),
                List.of(candidate(0, TARGET_A, Capability.CHAT),
                        candidate(1, TARGET_B, Capability.CHAT),
                        candidate(2, TARGET_C, Capability.CHAT)),
                List.of(routeCompleted(3)),
                List.of(
                        attempt(1, AttemptKind.INITIAL, TARGET_A, 0, AttemptOutcome.FAILED),
                        attempt(2, AttemptKind.FAILOVER, TARGET_B, 1, AttemptOutcome.FAILED),
                        attempt(3, AttemptKind.FAILOVER, TARGET_C, 2, AttemptOutcome.SUCCEEDED)),
                List.of());
    }

    private static CompletedCallDiagnostic streamFailedWithoutFailover(
            UUID callId, OffsetDateTime startedAt) {
        return new CompletedCallDiagnostic(
                callId, "request-" + callId, Capability.STREAM_CHAT, ServiceTier.BALANCED, null, true,
                "test-v1", "a".repeat(64),
                CallOutcome.STREAM_FAILED, ModelErrorType.STREAM_INTERRUPTED, null,
                TerminationReason.STREAM_INTERRUPTED_AFTER_COMMIT,
                null, null, true, null, null,
                new UsageDiagnostic(2, 1, null, null, null, null, null, 4),
                timing(startedAt),
                List.of(candidate(0, TARGET_A, Capability.STREAM_CHAT)),
                List.of(routeCompleted(1)),
                List.of(attempt(1, AttemptKind.INITIAL, TARGET_A, 0, AttemptOutcome.STREAM_FAILED)),
                List.of());
    }

    private static CompletedCallDiagnostic failedAfterTwoFailovers(
            UUID callId, OffsetDateTime startedAt) {
        return new CompletedCallDiagnostic(
                callId, "request-" + callId, Capability.CHAT, ServiceTier.BALANCED, null, false,
                "test-v1", "a".repeat(64),
                CallOutcome.FAILED, ModelErrorType.SERVER_ERROR, null,
                TerminationReason.ALL_CANDIDATES_EXHAUSTED,
                null, null, false, null, null,
                new UsageDiagnostic(null, null, null, null, null, null, null, null),
                timing(startedAt),
                List.of(candidate(0, TARGET_A, Capability.CHAT),
                        candidate(1, TARGET_B, Capability.CHAT),
                        candidate(2, TARGET_C, Capability.CHAT)),
                List.of(routeCompleted(3)),
                List.of(
                        attempt(1, AttemptKind.INITIAL, TARGET_A, 0, AttemptOutcome.FAILED),
                        attempt(2, AttemptKind.FAILOVER, TARGET_B, 1, AttemptOutcome.FAILED),
                        attempt(3, AttemptKind.FAILOVER, TARGET_C, 2, AttemptOutcome.FAILED)),
                List.of());
    }

    private static AttemptDiagnostic attempt(
            int attemptNo,
            AttemptKind kind,
            ExecutionTarget target,
            int candidateOrder,
            AttemptOutcome outcome) {
        ModelErrorType errorType = switch (outcome) {
            case SUCCEEDED -> null;
            case STREAM_FAILED -> ModelErrorType.STREAM_INTERRUPTED;
            default -> ModelErrorType.SERVER_ERROR;
        };
        return new AttemptDiagnostic(
                attemptNo, 0, null, null, candidateOrder, kind,
                attemptNo * 2, attemptNo * 2 + 1,
                target, QUOTA, BreakerState.CLOSED, BreakerState.CLOSED,
                outcome, errorType, null, null, null,
                0L, null, null, null, 5L, null, null, null);
    }

    private static RouteCandidateDiagnostic candidate(
            int ordinal, ExecutionTarget target, Capability capability) {
        return new RouteCandidateDiagnostic(
                ordinal, target, capability, ServiceTier.BALANCED, QUOTA,
                RouteDecision.ELIGIBLE, null, ordinal, ordinal == 0, 10, BreakerState.CLOSED);
    }

    private static RouteEventDiagnostic routeCompleted(int eligibleCandidateCount) {
        return new RouteEventDiagnostic(
                1, 0, RouteEventType.ROUTE_COMPLETED, null, null, null,
                null, null, null, null, null, eligibleCandidateCount);
    }

    private static TimingDiagnostic timing(OffsetDateTime startedAt) {
        return new TimingDiagnostic(1L, null, null, null, 8, startedAt, startedAt.plusSeconds(1));
    }

    private static ExecutionTarget target(String deploymentId) {
        return new ExecutionTarget(
                new ProviderId("provider-a"),
                new DeploymentId(deploymentId),
                new ModelIdentity(deploymentId + "-v1"));
    }

    private static UUID callId(int ordinal) {
        return UUID.fromString("00000000-0000-0000-0000-0000000005%02d".formatted(ordinal));
    }

    private static AiRuntimePolicy runtimePolicy() {
        return new AiRuntimePolicy(
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(45), 3, 2),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(120), 3, 1),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofMinutes(15), 3, 2),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(20), 3, 2),
                new AiRuntimePolicy.StreamLimits(32, 65_536, 65_536),
                new AiRuntimePolicy.BreakerLimits(
                        20, 5, 50, 50, Duration.ofSeconds(30), 2, Duration.ofSeconds(30)));
    }

    private String schemaUrl() {
        return baseUrl + "?currentSchema=" + schema + ",public";
    }

    private static String withoutQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static final class CountingRecorder implements DiagnosticRecorder {

        @Override
        public boolean offer(CompletedCallDiagnostic diagnostic) {
            return true;
        }

        @Override
        public long droppedCount() {
            return 0;
        }

        @Override
        public long writeFailureCount() {
            return 0;
        }
    }
}
