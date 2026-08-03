package com.app.ai.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class ModelSchedulingMigrationTests {

    private static final Set<String> DIAGNOSTIC_TABLES = Set.of(
            "ai_logical_call",
            "ai_route_candidate",
            "ai_route_event",
            "ai_attempt",
            "ai_breaker_transition");

    private final String baseUrl = withoutQuery(System.getProperty(
            "spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/app"));
    private final String username = System.getProperty("spring.datasource.username", "app");
    private final String password = System.getProperty("spring.datasource.password", "app");

    private String schema;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createIsolatedSchema() throws SQLException {
        schema = "xrag_model_sched_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        jdbc = new JdbcTemplate(new DriverManagerDataSource(schemaUrl(), username, password));
    }

    @AfterEach
    void dropIsolatedSchema() throws SQLException {
        if (schema == null) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void dia01_空Schema一次迁移后得到Canonical五表结构() {
        migrate(null);

        assertThat(tableNames()).containsExactlyInAnyOrderElementsOf(DIAGNOSTIC_TABLES);
        assertColumns("ai_logical_call", Set.of(
                "id", "request_id", "capability", "service_tier", "required_model_identity", "stream",
                "policy_version", "config_fingerprint", "outcome", "actual_provider_id",
                "actual_deployment_id", "actual_model_identity", "provider_request_id_masked",
                "normalized_error_type", "failure_phase", "termination_reason", "http_status",
                "finish_reason", "attempt_count", "retry_count", "failover_count", "mixed_execution",
                "contributing_deployment_count", "committed", "rerank_applied", "degraded_reason",
                "input_token_count", "output_token_count", "embedding_input_count", "embedding_batch_count",
                "embedding_dimensions", "rerank_document_count", "rerank_top_n", "stream_event_count",
                "route_duration_ms", "first_upstream_event_ms", "first_meaningful_chunk_ms",
                "downstream_commit_ms", "total_duration_ms", "started_at", "completed_at",
                "diagnostic_schema_version"));
        assertColumns("ai_route_candidate", Set.of(
                "logical_call_id", "candidate_ordinal", "provider_id", "deployment_id", "model_identity",
                "capability", "service_tier", "quota_domain", "decision", "rejection_reason",
                "planned_order", "default_candidate", "priority", "breaker_state_snapshot"));
        assertColumns("ai_route_event", Set.of(
                "id", "logical_call_id", "sequence_no", "occurred_offset_ms", "event_type",
                "work_unit_index", "attempt_no", "candidate_order", "provider_id", "deployment_id",
                "reason", "wait_duration_ms", "remaining_budget_ms", "eligible_candidate_count"));
        assertColumns("ai_attempt", Set.of(
                "id", "logical_call_id", "attempt_no", "work_unit_index", "input_from_inclusive",
                "input_to_exclusive", "candidate_order", "attempt_kind", "started_sequence_no",
                "completed_sequence_no", "provider_id", "deployment_id", "model_identity", "quota_domain",
                "breaker_state_before", "breaker_state_after", "outcome", "normalized_error_type",
                "failure_phase", "http_status", "provider_request_id_masked", "started_offset_ms",
                "first_upstream_event_ms", "first_meaningful_chunk_ms", "downstream_commit_ms",
                "duration_ms", "input_token_count", "output_token_count", "stream_event_count"));
        assertColumns("ai_breaker_transition", Set.of(
                "id", "breaker_key", "provider_id", "deployment_id", "model_identity", "capability",
                "from_state", "to_state", "transition_reason", "transitioned_at"));

        assertThat(indexNames()).contains(
                "idx_ai_call_started",
                "idx_ai_call_capability_started",
                "idx_ai_call_outcome_started",
                "idx_ai_call_provider_started",
                "idx_ai_call_request_id",
                "uk_ai_candidate_planned_order",
                "idx_ai_route_event_call",
                "idx_ai_attempt_call",
                "idx_ai_attempt_provider_call",
                "idx_ai_attempt_provider_request_id",
                "idx_ai_breaker_key_time",
                "idx_ai_breaker_time");
        assertThat(constraintNames()).contains(
                "ck_ai_call_capability",
                "ck_ai_call_stream",
                "ck_ai_call_finish_reason",
                "ck_ai_call_rerank_result",
                "ck_ai_call_execution_summary",
                "ck_ai_call_termination_reason",
                "ck_ai_call_error_type",
                "ck_ai_call_failure_phase",
                "uk_ai_route_event_sequence",
                "uk_ai_attempt_no",
                "ck_ai_attempt_failure_phase");
        assertThat(cascadeChildTables())
                .containsExactlyInAnyOrder("ai_route_candidate", "ai_route_event", "ai_attempt");
        assertThat(allDiagnosticColumns())
                .noneMatch(name -> name.matches(".*(prompt|message|content|raw_body|authorization|secret|tool_arguments).*"));
    }

    @Test
    void v6在已有Phase1Schema上迁移且不修改业务表或数据() {
        migrate("5");
        Set<String> beforeColumns = columns("knowledge_base");
        jdbc.update("""
                insert into knowledge_base
                    (name, embedding_model, embedding_dimensions, storage_alias)
                values ('phase1-existing', 'text-embedding-v3', 1024, 'phase1-existing')
                """);

        migrate(null);

        assertThat(columns("knowledge_base")).isEqualTo(beforeColumns);
        assertThat(jdbc.queryForObject(
                "select count(*) from knowledge_base where name = 'phase1-existing'", Integer.class))
                .isEqualTo(1);
        assertThat(tableNames()).containsExactlyInAnyOrderElementsOf(DIAGNOSTIC_TABLES);
    }

    @Test
    void schema约束拒绝矛盾摘要并级联删除三张调用子表() {
        migrate(null);

        assertThatThrownBy(() -> insertLogicalCall(
                        UUID.randomUUID(), "CHAT", true, "FAILED", null, null, "NO_ELIGIBLE_MODEL", 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ai_call_stream");
        assertThatThrownBy(() -> insertLogicalCall(
                        UUID.randomUUID(), "CHAT", false, "FAILED", "BOGUS", null, "NO_ELIGIBLE_MODEL", 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ai_call_finish_reason");
        assertThatThrownBy(() -> insertLogicalCall(
                        UUID.randomUUID(), "RERANK", false, "DEGRADED", null, "BOGUS", "NOOP_DEGRADED", 0))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_ai_call_rerank_result");

        UUID callId = UUID.randomUUID();
        insertLogicalCall(callId, "CHAT", false, "SUCCEEDED", "STOP", null, "COMPLETED", 1);
        jdbc.update("""
                insert into ai_route_candidate
                    (logical_call_id, candidate_ordinal, provider_id, deployment_id, model_identity,
                     capability, service_tier, quota_domain, decision, planned_order,
                     default_candidate, priority)
                values (?, 0, 'provider-a', 'deployment-a', 'model-a',
                        'CHAT', 'BALANCED', 'quota-a', 'ELIGIBLE', 0, true, 10)
                """, callId);
        jdbc.update("""
                insert into ai_route_event
                    (logical_call_id, sequence_no, occurred_offset_ms, event_type, eligible_candidate_count)
                values (?, 1, 0, 'ROUTE_COMPLETED', 1)
                """, callId);
        jdbc.update("""
                insert into ai_attempt
                    (logical_call_id, attempt_no, work_unit_index, candidate_order, attempt_kind,
                     started_sequence_no, completed_sequence_no, provider_id, deployment_id,
                     model_identity, quota_domain, breaker_state_before, outcome,
                     started_offset_ms, duration_ms)
                values (?, 1, 0, 0, 'INITIAL', 2, 3, 'provider-a', 'deployment-a',
                        'model-a', 'quota-a', 'CLOSED', 'SUCCEEDED', 1, 5)
                """, callId);
        jdbc.update("""
                insert into ai_breaker_transition
                    (breaker_key, provider_id, deployment_id, model_identity, capability,
                     from_state, to_state, transition_reason, transitioned_at)
                values ('provider-a/deployment-a/CHAT', 'provider-a', 'deployment-a', 'model-a', 'CHAT',
                        'CLOSED', 'OPEN', 'FAILURE_RATE', ?)
                """, OffsetDateTime.now());

        assertThatThrownBy(() -> jdbc.update("""
                    insert into ai_route_event
                        (logical_call_id, sequence_no, occurred_offset_ms, event_type, eligible_candidate_count)
                    values (?, 1, 2, 'ROUTE_COMPLETED', 1)
                    """, callId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_ai_route_event_sequence");

        jdbc.update("delete from ai_logical_call where id = ?", callId);

        assertThat(jdbc.queryForObject(
                "select count(*) from ai_route_candidate where logical_call_id = ?", Integer.class, callId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from ai_route_event where logical_call_id = ?", Integer.class, callId))
                .isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from ai_attempt where logical_call_id = ?", Integer.class, callId))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from ai_breaker_transition", Integer.class))
                .isEqualTo(1);
    }

    private void migrate(String targetVersion) {
        var configuration = Flyway.configure()
                .dataSource(schemaUrl(), username, password)
                .defaultSchema(schema)
                .schemas(schema)
                .createSchemas(true)
                .locations("classpath:db/migration");
        if (targetVersion != null) {
            configuration.target(MigrationVersion.fromVersion(targetVersion));
        }
        configuration.load().migrate();
    }

    private void insertLogicalCall(
            UUID id,
            String capability,
            boolean stream,
            String outcome,
            String finishReason,
            String degradedReason,
            String terminationReason,
            int contributorCount) {
        boolean rerankApplied = "RERANK".equals(capability) && "SUCCEEDED".equals(outcome);
        Object rerankValue = "RERANK".equals(capability) && ("SUCCEEDED".equals(outcome) || "DEGRADED".equals(outcome))
                ? rerankApplied
                : null;
        boolean hasContributor = contributorCount == 1;
        jdbc.update("""
                insert into ai_logical_call
                    (id, request_id, capability, service_tier, required_model_identity, stream,
                     policy_version, config_fingerprint, outcome,
                     actual_provider_id, actual_deployment_id, actual_model_identity,
                     termination_reason, finish_reason, attempt_count, retry_count, failover_count,
                     mixed_execution, contributing_deployment_count, committed,
                     rerank_applied, degraded_reason, total_duration_ms, started_at, completed_at)
                values (?, 'request-1', ?, ?, ?, ?, 'test-v1', ?, ?, ?, ?, ?, ?, ?, ?, 0, 0,
                        false, ?, false, ?, ?, 1, ?, ?)
                """,
                id,
                capability,
                "EMBEDDING".equals(capability) ? null : "BALANCED",
                "EMBEDDING".equals(capability) ? "embed-v1" : null,
                stream,
                "0".repeat(64),
                outcome,
                hasContributor ? "provider-a" : null,
                hasContributor ? "deployment-a" : null,
                hasContributor ? "model-a" : null,
                terminationReason,
                finishReason,
                contributorCount,
                contributorCount,
                rerankValue,
                degradedReason,
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    private Set<String> tableNames() {
        return Set.copyOf(jdbc.queryForList("""
                select table_name
                  from information_schema.tables
                 where table_schema = ? and table_name like 'ai_%'
                """, String.class, schema));
    }

    private Set<String> columns(String table) {
        return Set.copyOf(jdbc.queryForList("""
                select column_name
                  from information_schema.columns
                 where table_schema = ? and table_name = ?
                """, String.class, schema, table));
    }

    private void assertColumns(String table, Set<String> expected) {
        assertThat(columns(table)).as(table + " columns").isEqualTo(expected);
    }

    private Set<String> indexNames() {
        return Set.copyOf(jdbc.queryForList(
                "select indexname from pg_indexes where schemaname = ? and tablename like 'ai_%'",
                String.class,
                schema));
    }

    private Set<String> constraintNames() {
        return Set.copyOf(jdbc.queryForList("""
                select constraint_name
                  from information_schema.table_constraints
                 where constraint_schema = ? and table_name like 'ai_%'
                """, String.class, schema));
    }

    private List<String> cascadeChildTables() {
        return jdbc.queryForList("""
                select tc.table_name
                  from information_schema.referential_constraints rc
                  join information_schema.table_constraints tc
                    on tc.constraint_schema = rc.constraint_schema
                   and tc.constraint_name = rc.constraint_name
                 where rc.constraint_schema = ?
                   and rc.delete_rule = 'CASCADE'
                   and tc.table_name like 'ai_%'
                """, String.class, schema);
    }

    private List<String> allDiagnosticColumns() {
        return jdbc.queryForList("""
                select column_name
                  from information_schema.columns
                 where table_schema = ? and table_name like 'ai_%'
                """, String.class, schema);
    }

    private String schemaUrl() {
        return baseUrl + "?currentSchema=" + schema + ",public";
    }

    private static String withoutQuery(String url) {
        int queryStart = url.indexOf('?');
        return queryStart < 0 ? url : url.substring(0, queryStart);
    }
}
