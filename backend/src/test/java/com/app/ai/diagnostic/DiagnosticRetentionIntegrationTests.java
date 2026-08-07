package com.app.ai.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.breaker.BreakerKey;
import com.app.ai.breaker.BreakerTransitionReason;
import com.app.ai.model.Capability;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ExecutionTarget;
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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** DIA-13~DIA-16：诊断保留与分批清理。 */
class DiagnosticRetentionIntegrationTests {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-07T12:00:00Z");
    private static final QuotaDomain QUOTA = new QuotaDomain("quota-a");
    private static final ExecutionTarget TARGET = new ExecutionTarget(
            new ProviderId("provider-a"),
            new DeploymentId("chat-a"),
            new ModelIdentity("chat-a-v1"));

    private final String baseUrl = withoutQuery(System.getProperty(
            "spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/app"));
    private final String username = System.getProperty("spring.datasource.username", "app");
    private final String password = System.getProperty("spring.datasource.password", "app");

    private String schema;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createSchema() throws SQLException {
        schema = "xrag_diag_retention_" + UUID.randomUUID().toString().replace("-", "");
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
    void dia13_超过保留期的调用连同四张子表一起物理删除() {
        var store = new JdbcDiagnosticStore(jdbc, transactionManager());
        UUID expired = UUID.fromString("00000000-0000-0000-0000-000000000801");
        UUID fresh = UUID.fromString("00000000-0000-0000-0000-000000000802");
        store.write(call(expired, NOW.minusDays(8)));
        store.write(call(fresh, NOW.minusDays(6)));

        var result = service(7, 10_000, 1_000).runOnce();

        assertThat(result.deletedCallsByAge()).isEqualTo(1);
        assertThat(ids("ai_logical_call")).containsExactly(fresh);
        // cascade 必须真的把可派生的执行事实带走，不能只剩下孤儿子表行。
        assertThat(countWhere("ai_route_candidate", expired)).isZero();
        assertThat(countWhere("ai_route_event", expired)).isZero();
        assertThat(countWhere("ai_attempt", expired)).isZero();
        assertThat(countWhere("ai_attempt", fresh)).isEqualTo(1);
    }

    @Test
    void dia14_保留期内超出条数上限时只留最新的那批() {
        insertBulkCalls(10_001, NOW.minusHours(1));

        var result = service(7, 10_000, 1_000).runOnce();

        assertThat(result.deletedCallsByAge()).isZero();
        assertThat(result.deletedCallsByCount()).isEqualTo(1);
        assertThat(count("ai_logical_call")).isEqualTo(10_000);
        // 按 started_at desc, id desc 稳定排序，被删的必须是最旧那条而不是任意一条：
        // 造数从 base+0s 起每条递增 1 秒，删掉一条后最早的应该正好是 base+1s。
        assertThat(jdbc.queryForObject(
                "select min(started_at) from ai_logical_call", OffsetDateTime.class))
                .isEqualTo(NOW.minusHours(1).plusSeconds(1).withOffsetSameInstant(ZoneOffset.UTC));
    }

    @Test
    void dia15_breakerTransition只按时间保留不受条数上限影响() {
        insertBulkCalls(10_001, NOW.minusHours(1));
        insertTransition(NOW.minusDays(8));
        insertTransition(NOW.minusDays(6));

        var result = service(7, 10_000, 1_000).runOnce();

        // 调用按条数上限清理过，但 transition 的去留只由 7 天决定：删掉 8 天前那条，留下 6 天前那条。
        assertThat(result.deletedCallsByCount()).isEqualTo(1);
        assertThat(result.deletedTransitions()).isEqualTo(1);
        assertThat(count("ai_breaker_transition")).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select transitioned_at from ai_breaker_transition", OffsetDateTime.class))
                .isEqualTo(NOW.minusDays(6).withOffsetSameInstant(ZoneOffset.UTC));
    }

    @Test
    void dia16_一次要删很多条时拆成多个短事务而不是一个大删除() {
        insertBulkCalls(2_500, NOW.minusDays(8));

        // 用生产默认的 batchSize=1000：2500 条按时间清理需要 1000+1000+500 三次删除事务。
        var result = service(7, 10_000, 1_000).runOnce();

        assertThat(result.deletedCallsByAge()).isEqualTo(2_500);
        assertThat(count("ai_logical_call")).isZero();
        // 三项清理各自至少 1 次确认，按时间那项占 3 次，合计 5——单个大事务只会是 3。
        assertThat(result.passes()).isEqualTo(5);
    }

    @Test
    void 无数据可删时不会空转成死循环也不报错() {
        var result = service(7, 10_000, 1_000).runOnce();

        assertThat(result.deletedCalls()).isZero();
        assertThat(result.deletedTransitions()).isZero();
        // 三项各自一次"什么都没删"的确认。
        assertThat(result.passes()).isEqualTo(3);
    }

    private DiagnosticRetentionService service(int maxAgeDays, int maxLogicalCalls, int batchSize) {
        return new DiagnosticRetentionService(
                jdbc,
                transactionManager(),
                Clock.fixed(NOW.toInstant(), ZoneOffset.UTC),
                maxAgeDays,
                maxLogicalCalls,
                batchSize,
                true);
    }

    private DataSourceTransactionManager transactionManager() {
        return new DataSourceTransactionManager(dataSource);
    }

    /**
     * 批量造数走单条 SQL：DIA-14 需要 10,001 条，逐条走 writer 会把用例拖成分钟级。
     * 本组用例验的是删除口径，不是写入语义，第 n 条的 {@code started_at} 递增 1 秒以保证顺序稳定。
     */
    private void insertBulkCalls(int rows, OffsetDateTime oldestStartedAt) {
        jdbc.update("""
                insert into ai_logical_call (
                    id, request_id, capability, stream, policy_version, config_fingerprint,
                    outcome, actual_provider_id, actual_deployment_id, actual_model_identity,
                    termination_reason, attempt_count, retry_count, failover_count,
                    mixed_execution, contributing_deployment_count, committed,
                    total_duration_ms, started_at, completed_at)
                select
                    gen_random_uuid(), 'request-' || n, 'CHAT', false, 'test-v1', ?,
                    'SUCCEEDED', 'provider-a', 'chat-a', 'chat-a-v1',
                    'COMPLETED', 1, 0, 0, false, 1, true,
                    5, ? + make_interval(secs => n), ? + make_interval(secs => n)
                from generate_series(0, ? - 1) as n
                """,
                "a".repeat(64), oldestStartedAt, oldestStartedAt, rows);
    }

    private void insertTransition(OffsetDateTime transitionedAt) {
        new JdbcDiagnosticStore(jdbc, transactionManager()).write(new BreakerTransitionDiagnostic(
                new BreakerKey(TARGET.providerId(), TARGET.deploymentId(), Capability.CHAT),
                TARGET,
                BreakerState.CLOSED,
                BreakerState.OPEN,
                BreakerTransitionReason.FAILURE_RATE,
                transitionedAt));
    }

    private static CompletedCallDiagnostic call(UUID callId, OffsetDateTime startedAt) {
        return new CompletedCallDiagnostic(
                callId, "request-" + callId, Capability.CHAT, ServiceTier.BALANCED, null, false,
                "test-v1", "a".repeat(64),
                CallOutcome.SUCCEEDED, null, null, TerminationReason.COMPLETED,
                null, ChatFinishReason.STOP, true, null, null,
                new UsageDiagnostic(2, 1, null, null, null, null, null, null),
                new TimingDiagnostic(1L, null, null, null, 8, startedAt, startedAt.plusSeconds(1)),
                List.of(new RouteCandidateDiagnostic(
                        0, TARGET, Capability.CHAT, ServiceTier.BALANCED, QUOTA,
                        RouteDecision.ELIGIBLE, null, 0, true, 10, BreakerState.CLOSED)),
                List.of(new RouteEventDiagnostic(
                        1, 0, RouteEventType.ROUTE_COMPLETED, null, null, null,
                        null, null, null, null, null, 1)),
                List.of(new AttemptDiagnostic(
                        1, 0, null, null, 0, AttemptKind.INITIAL, 2, 3,
                        TARGET, QUOTA, BreakerState.CLOSED, BreakerState.CLOSED,
                        AttemptOutcome.SUCCEEDED, null, null, 200, null,
                        0L, null, null, null, 5L, 2, 1, null)),
                List.of());
    }

    private long count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Long.class);
    }

    private long countWhere(String table, UUID callId) {
        return jdbc.queryForObject(
                "select count(*) from " + table + " where logical_call_id = ?", Long.class, callId);
    }

    private List<UUID> ids(String table) {
        return jdbc.queryForList("select id from " + table + " order by started_at", UUID.class);
    }

    private String schemaUrl() {
        return baseUrl + "?currentSchema=" + schema + ",public";
    }

    private static String withoutQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
}
