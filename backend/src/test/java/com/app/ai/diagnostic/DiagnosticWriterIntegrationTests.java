package com.app.ai.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.ai.breaker.BreakerPermission;
import com.app.ai.breaker.Resilience4jModelBreakerRegistry;
import com.app.ai.model.AiRuntimePolicy;
import com.app.ai.model.Capability;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ProviderId;
import com.app.ai.model.QuotaDomain;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.TerminationReason;
import com.app.ai.provider.AttemptFailureType;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class DiagnosticWriterIntegrationTests {

    private final String baseUrl = withoutQuery(System.getProperty(
            "spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/app"));
    private final String username = System.getProperty("spring.datasource.username", "app");
    private final String password = System.getProperty("spring.datasource.password", "app");

    private String schema;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createSchema() throws SQLException {
        schema = "xrag_diag_writer_" + UUID.randomUUID().toString().replace("-", "");
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
    void dia02_成功调用的全部事实在关闭flush后一次落库() {
        var store = new JdbcDiagnosticStore(jdbc, new DataSourceTransactionManager(dataSource));
        var writer = new AsyncDiagnosticWriter(
                store, 1000, 50, Duration.ofMillis(250), Duration.ofSeconds(2));
        UUID callId = UUID.fromString("00000000-0000-0000-0000-000000000101");

        assertThat(writer.offer(successfulCall(callId))).isTrue();
        writer.close();

        assertThat(count("ai_logical_call", "id", callId)).isEqualTo(1);
        assertThat(count("ai_route_candidate", "logical_call_id", callId)).isEqualTo(1);
        assertThat(count("ai_route_event", "logical_call_id", callId)).isEqualTo(1);
        assertThat(count("ai_attempt", "logical_call_id", callId)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select actual_deployment_id from ai_logical_call where id = ?",
                String.class,
                callId)).isEqualTo("chat-a");
    }

    @Test
    void dia09_任一子记录失败时整条调用回滚() {
        var store = new JdbcDiagnosticStore(jdbc, new DataSourceTransactionManager(dataSource));
        UUID callId = UUID.fromString("00000000-0000-0000-0000-000000000102");
        var valid = successfulCall(callId);
        var invalid = withCandidates(valid, List.of(valid.candidates().get(0), valid.candidates().get(0)));

        assertThatThrownBy(() -> store.write(invalid)).isInstanceOf(RuntimeException.class);

        assertThat(count("ai_logical_call", "id", callId)).isZero();
        assertThat(count("ai_route_candidate", "logical_call_id", callId)).isZero();
    }

    @Test
    void dia04_无候选与permissionReject在attempt为零时仍完整落库() {
        var store = new JdbcDiagnosticStore(jdbc, new DataSourceTransactionManager(dataSource));
        UUID callId = UUID.fromString("00000000-0000-0000-0000-000000000103");

        store.write(attemptZeroPermissionRejectedCall(callId));

        assertThat(count("ai_logical_call", "id", callId)).isEqualTo(1);
        assertThat(count("ai_route_candidate", "logical_call_id", callId)).isEqualTo(1);
        assertThat(count("ai_route_event", "logical_call_id", callId)).isEqualTo(2);
        assertThat(count("ai_attempt", "logical_call_id", callId)).isZero();
        assertThat(jdbc.queryForObject(
                "select termination_reason from ai_logical_call where id = ?",
                String.class,
                callId)).isEqualTo("NO_ELIGIBLE_MODEL");
    }

    @Test
    void mixedEmbedding由成功attempt推导贡献摘要且不写独立贡献表() {
        var store = new JdbcDiagnosticStore(jdbc, new DataSourceTransactionManager(dataSource));
        UUID callId = UUID.fromString("00000000-0000-0000-0000-000000000104");

        store.write(mixedEmbeddingCall(callId));

        var summary = jdbc.queryForMap("""
                select mixed_execution, contributing_deployment_count,
                       actual_provider_id, actual_deployment_id, actual_model_identity
                  from ai_logical_call
                 where id = ?
                """, callId);
        assertThat(summary.get("mixed_execution")).isEqualTo(true);
        assertThat(summary.get("contributing_deployment_count")).isEqualTo(2);
        assertThat(summary.get("actual_provider_id")).isNull();
        assertThat(count("ai_attempt", "logical_call_id", callId)).isEqualTo(2);
        assertThat(jdbc.queryForList(
                "select distinct work_unit_index from ai_attempt where logical_call_id = ? order by 1",
                Integer.class,
                callId)).containsExactly(0, 1);
    }

    @Test
    void cbr13_breaker真实transition通过异步writer以稳定原因独立落库() {
        var store = new JdbcDiagnosticStore(jdbc, new DataSourceTransactionManager(dataSource));
        var writer = new AsyncDiagnosticWriter(
                store, 1000, 50, Duration.ofMillis(250), Duration.ofSeconds(2));
        var target = new ExecutionTarget(
                new ProviderId("provider-a"),
                new DeploymentId("chat-a"),
                new ModelIdentity("chat-model-v1"));
        var registry = new Resilience4jModelBreakerRegistry(
                new AiRuntimePolicy.BreakerLimits(
                        4,
                        4,
                        50,
                        50,
                        Duration.ofSeconds(30),
                        2,
                        Duration.ofSeconds(30)),
                Clock.systemUTC(),
                writer);

        for (int call = 0; call < 4; call++) {
            ((BreakerPermission) registry.tryAcquire(target, Capability.CHAT))
                    .fail(Duration.ofMillis(10), AttemptFailureType.CONNECT_FAILED);
        }
        writer.close();

        assertThat(jdbc.queryForList("""
                select breaker_key, provider_id, deployment_id, model_identity, capability,
                       from_state, to_state, transition_reason, transitioned_at
                  from ai_breaker_transition
                """)).singleElement().satisfies(row -> {
            assertThat(row.get("breaker_key")).isEqualTo("provider-a/chat-a/CHAT");
            assertThat(row.get("provider_id")).isEqualTo("provider-a");
            assertThat(row.get("deployment_id")).isEqualTo("chat-a");
            assertThat(row.get("model_identity")).isEqualTo("chat-model-v1");
            assertThat(row.get("capability")).isEqualTo("CHAT");
            assertThat(row.get("from_state")).isEqualTo("CLOSED");
            assertThat(row.get("to_state")).isEqualTo("OPEN");
            assertThat(row.get("transition_reason")).isEqualTo("FAILURE_RATE");
            assertThat(row.get("transitioned_at")).isNotNull();
        });
    }

    private static CompletedCallDiagnostic successfulCall(UUID callId) {
        var target = new ExecutionTarget(
                new ProviderId("provider-a"),
                new DeploymentId("chat-a"),
                new ModelIdentity("chat-model-v1"));
        var candidate = new RouteCandidateDiagnostic(
                0,
                target,
                Capability.CHAT,
                ServiceTier.BALANCED,
                new QuotaDomain("quota-a"),
                RouteDecision.ELIGIBLE,
                null,
                0,
                true,
                10,
                BreakerState.CLOSED);
        var event = new RouteEventDiagnostic(
                1, 0, RouteEventType.ROUTE_COMPLETED, null, null, null,
                null, null, null, null, null, 1);
        var attempt = new AttemptDiagnostic(
                1, 0, null, null, 0, AttemptKind.INITIAL, 2, 3,
                target, new QuotaDomain("quota-a"), BreakerState.CLOSED, BreakerState.CLOSED,
                AttemptOutcome.SUCCEEDED, null, null, 200, "req-***-42",
                1, 2L, null, null, 5, 2, 1, null);
        return new CompletedCallDiagnostic(
                callId,
                "request-1",
                Capability.CHAT,
                ServiceTier.BALANCED,
                null,
                false,
                "phase2-v1",
                "a".repeat(64),
                CallOutcome.SUCCEEDED,
                null,
                null,
                TerminationReason.COMPLETED,
                null,
                ChatFinishReason.STOP,
                false,
                null,
                null,
                new UsageDiagnostic(2, 1, null, null, null, null, null, null),
                new TimingDiagnostic(1L, null, null, null, 8,
                        OffsetDateTime.parse("2026-08-03T00:00:00Z"),
                        OffsetDateTime.parse("2026-08-03T00:00:00.008Z")),
                List.of(candidate),
                List.of(event),
                List.of(attempt),
                List.of());
    }

    private static CompletedCallDiagnostic attemptZeroPermissionRejectedCall(UUID callId) {
        var target = new ExecutionTarget(
                new ProviderId("provider-a"),
                new DeploymentId("chat-a"),
                new ModelIdentity("chat-model-v1"));
        var candidate = new RouteCandidateDiagnostic(
                0, target, Capability.CHAT, ServiceTier.BALANCED, new QuotaDomain("quota-a"),
                RouteDecision.ELIGIBLE, null, 0, true, 10, BreakerState.HALF_OPEN);
        OffsetDateTime now = OffsetDateTime.parse("2026-08-03T00:00:00Z");
        return new CompletedCallDiagnostic(
                callId,
                "request-3",
                Capability.CHAT,
                ServiceTier.BALANCED,
                null,
                false,
                "phase2-v1",
                "c".repeat(64),
                CallOutcome.FAILED,
                ModelErrorType.NO_ELIGIBLE_MODEL,
                null,
                TerminationReason.NO_ELIGIBLE_MODEL,
                null,
                null,
                false,
                null,
                null,
                new UsageDiagnostic(null, null, null, null, null, null, null, null),
                new TimingDiagnostic(1L, null, null, null, 1, now, now.plusNanos(1_000_000)),
                List.of(candidate),
                List.of(
                        new RouteEventDiagnostic(
                                1, 0, RouteEventType.ROUTE_COMPLETED, null, null, null,
                                null, null, null, null, null, 1),
                        new RouteEventDiagnostic(
                                2, 1, RouteEventType.BREAKER_PERMISSION_REJECTED, 0, null, 0,
                                target.providerId(), target.deploymentId(), "BREAKER_OPEN", null, 0L, null)),
                List.of(),
                List.of());
    }

    private static CompletedCallDiagnostic mixedEmbeddingCall(UUID callId) {
        var first = new ExecutionTarget(
                new ProviderId("provider-a"),
                new DeploymentId("embedding-a"),
                new ModelIdentity("embed-v1"));
        var second = new ExecutionTarget(
                new ProviderId("provider-b"),
                new DeploymentId("embedding-b"),
                new ModelIdentity("embed-v1"));
        var attempt1 = embeddingAttempt(1, 0, 0, 2, first, AttemptKind.INITIAL, 2, 3);
        var attempt2 = embeddingAttempt(2, 1, 2, 3, second, AttemptKind.FAILOVER, 4, 5);
        OffsetDateTime now = OffsetDateTime.parse("2026-08-03T00:00:00Z");
        return new CompletedCallDiagnostic(
                callId,
                "request-4",
                Capability.EMBEDDING,
                null,
                new ModelIdentity("embed-v1"),
                false,
                "phase2-v1",
                "d".repeat(64),
                CallOutcome.SUCCEEDED,
                null,
                null,
                TerminationReason.COMPLETED,
                null,
                null,
                false,
                null,
                null,
                new UsageDiagnostic(null, null, 3, 2, 2, null, null, null),
                new TimingDiagnostic(1L, null, null, null, 6, now, now.plusNanos(6_000_000)),
                List.of(
                        embeddingCandidate(0, first, 0),
                        embeddingCandidate(1, second, 1)),
                List.of(new RouteEventDiagnostic(
                        1, 0, RouteEventType.ROUTE_COMPLETED, null, null, null,
                        null, null, null, null, null, 2)),
                List.of(attempt1, attempt2),
                List.of(
                        new EmbeddingBatchExecutionDiagnostic(0, 0, 2, first, 1, 0, 0),
                        new EmbeddingBatchExecutionDiagnostic(1, 2, 3, second, 1, 0, 1)));
    }

    private static RouteCandidateDiagnostic embeddingCandidate(
            int ordinal, ExecutionTarget target, int plannedOrder) {
        return new RouteCandidateDiagnostic(
                ordinal, target, Capability.EMBEDDING, null, new QuotaDomain("quota-" + ordinal),
                RouteDecision.ELIGIBLE, null, plannedOrder, false, 10, BreakerState.CLOSED);
    }

    private static AttemptDiagnostic embeddingAttempt(
            int attemptNo,
            int workUnit,
            int from,
            int to,
            ExecutionTarget target,
            AttemptKind kind,
            int startedSequence,
            int completedSequence) {
        return new AttemptDiagnostic(
                attemptNo, workUnit, from, to, workUnit, kind, startedSequence, completedSequence,
                target, new QuotaDomain("quota-" + workUnit), BreakerState.CLOSED, BreakerState.CLOSED,
                AttemptOutcome.SUCCEEDED, null, null, 200, "req-***-" + attemptNo,
                startedSequence, null, null, null, 1, null, null, null);
    }

    private static CompletedCallDiagnostic withCandidates(
            CompletedCallDiagnostic source,
            List<RouteCandidateDiagnostic> candidates) {
        return new CompletedCallDiagnostic(
                source.logicalCallId(),
                source.requestId(),
                source.capability(),
                source.serviceTier(),
                source.requiredModelIdentity(),
                source.stream(),
                source.policyVersion(),
                source.configFingerprint(),
                source.outcome(),
                source.normalizedErrorType(),
                source.failurePhase(),
                source.terminationReason(),
                source.httpStatus(),
                source.finishReason(),
                source.committed(),
                source.rerankApplied(),
                source.degradedReason(),
                source.usage(),
                source.timing(),
                candidates,
                source.routeEvents(),
                source.attempts(),
                source.embeddingBatches());
    }

    private int count(String table, String idColumn, UUID callId) {
        return jdbc.queryForObject(
                "select count(*) from " + table + " where " + idColumn + " = ?",
                Integer.class,
                callId);
    }

    private String schemaUrl() {
        return baseUrl + "?currentSchema=" + schema + ",public";
    }

    private static String withoutQuery(String jdbcUrl) {
        int query = jdbcUrl.indexOf('?');
        return query < 0 ? jdbcUrl : jdbcUrl.substring(0, query);
    }
}
