package com.app.qa;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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

/**
 * V9 与 3B DDL 约束（工单 02）。
 *
 * <p>这一组的重点不是"表建出来了"，而是**把产品语义落成数据库约束**：同轮片段唯一、
 * 降级与分数互斥、有副作用工具未确认不得进 RUNNING。它们放在数据库上的价值是即使某天
 * 服务层写错一个分支，也落不了地。
 */
class Qa3bSchemaMigrationTests {

    private static final Set<String> TABLES_3B = Set.of(
            "qa_turn_retrieval", "qa_retrieval_source", "qa_retrieval_target", "qa_tool_call");

    private final String baseUrl = withoutQuery(
            System.getProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/app"));
    private final String username = System.getProperty("spring.datasource.username", "app");
    private final String password = System.getProperty("spring.datasource.password", "app");

    private String schema;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createIsolatedSchema() throws SQLException {
        schema = "xrag_qa3b_" + UUID.randomUUID().toString().replace("-", "");
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
    void 从V8升级到V9创建全部3B表且可重复执行() {
        migrate("8");
        assertThat(tableNames()).doesNotContainAnyElementsOf(TABLES_3B);

        migrate(null);

        assertThat(tableNames()).containsAll(TABLES_3B).contains("document_chunk_keyword");
        // 幂等：再跑一次不报错。
        migrate(null);
    }

    @Test
    void V9不修改qaTurn状态闭集() {
        // 3B 用独立表表达自己的状态，不把 ACTIONS_READY 降级成中间态——那会让所有 3A
        // 历史 turn 的语义随代码改变（数据模型 §14.1）。
        migrate(null);
        String check = jdbc.queryForObject(
                """
                select pg_get_constraintdef(c.oid)
                from pg_constraint c
                join pg_class t on t.oid = c.conrelid
                join pg_namespace n on n.oid = t.relnamespace
                where n.nspname = ? and t.relname = 'qa_turn' and c.conname = 'ck_qa_turn_status'
                """,
                String.class,
                schema);
        assertThat(check)
                .contains("ACTIONS_READY")
                .doesNotContain("RETRIEVING")
                .doesNotContain("RETRIEVED");
    }

    @Test
    void 关键词派生表形态与向量派生表对齐() {
        migrate(null);
        // 三条不变量里"无 deleted 列"是可以直接断言的那条；行集合一致性属于写入点的职责，
        // 由工单 03 的测试正面断言。
        assertThat(columns("document_chunk_keyword"))
                .containsExactlyInAnyOrder("chunk_id", "kb_id", "doc_id", "tsv", "create_time");
        assertThat(columns("document_chunk_embedding")).doesNotContain("deleted");
        assertThat(columns("document_chunk_keyword")).doesNotContain("deleted");
    }

    @Test
    void 同一轮同一片段只能出现一次() {
        migrate(null);
        var ids = seedTurnWithAction();
        insertSource(ids, 0, 8801L);

        assertThatThrownBy(() -> insertSource(ids, 1, 8801L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_qa_source_chunk");
    }

    @Test
    void 来源序号在同一轮内唯一() {
        migrate(null);
        var ids = seedTurnWithAction();
        insertSource(ids, 0, 8801L);

        assertThatThrownBy(() -> insertSource(ids, 0, 8802L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_qa_source_ordinal");
    }

    @Test
    void tool10_有副作用的工具没有确认时间戳就不能处于执行中及之后() {
        // ck_qa_tool_confirm 是"确认前一律不执行"（PRD §12.7）的数据库表达。价值在于即使某天
        // 服务层写错一个分支，数据库也不让它落地——所以要有一条正面撞上去的用例，
        // 否则这个约束的存在性只靠读迁移脚本证明。
        migrate(null);
        var ids = seedTurnWithAction();

        for (String status : java.util.List.of("RUNNING", "SUCCEEDED", "FAILED")) {
            assertThatThrownBy(() -> insertToolCall(ids.turnId(), status, true, null))
                    .as("有副作用 + %s 却没有 confirmed_at，必须被拦住", status)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_qa_tool_confirm");
        }

        // 反过来也拦：没到执行阶段却带着确认时间戳，同样是状态机被绕过的信号。
        assertThatThrownBy(() -> insertToolCall(ids.turnId(), "PENDING_CONFIRM", true, java.time.OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_qa_tool_confirm");

        // 只读工具不需要确认，到 RUNNING 也不该有确认时间戳。
        assertThatCode(() -> insertToolCall(ids.turnId(), "RUNNING", false, null))
                .doesNotThrowAnyException();
    }

    private void insertToolCall(
            java.util.UUID turnId, String status, boolean sideEffect, java.time.OffsetDateTime confirmedAt) {
        jdbc.update(
                """
                insert into qa_tool_call
                    (id, turn_id, ordinal, tool_name, side_effect, status, failure_code, confirmed_at)
                values (?, ?, ?, 'submit-return-ticket', ?, ?, ?, ?)
                """,
                java.util.UUID.randomUUID(),
                turnId,
                toolOrdinal++,
                sideEffect,
                status,
                "FAILED".equals(status) ? "EXECUTION_FAILED" : null,
                confirmedAt);
    }

    /** 同一轮里 ordinal 唯一，本类多次插入工具行时靠它避开 uq_qa_tool_ordinal。 */
    private int toolOrdinal = 0;

    @Test
    void 降级与精排标记必须互斥() {
        migrate(null);
        var ids = seedTurnWithAction();

        assertThatThrownBy(() -> insertRetrieval(ids.turnId(), "RETRIEVED", true, "RERANK_UNAVAILABLE"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_qa_retrieval_rerank");
        assertThatThrownBy(() -> insertRetrieval(ids.turnId(), "RETRIEVED", false, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_qa_retrieval_rerank");

        assertThatCode(() -> insertRetrieval(ids.turnId(), "RETRIEVED", true, null)).doesNotThrowAnyException();
    }

    @Test
    void 漏斗计数必须单调不增() {
        migrate(null);
        var ids = seedTurnWithAction();

        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into qa_turn_retrieval
                            (turn_id, status, rerank_applied, channel_hits, after_dedup, after_rerank)
                        values (?, 'RETRIEVED', true, 5, 9, 3)
                        """,
                        ids.turnId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_qa_retrieval_funnel");
    }

    @Test
    void 失败目标必须带归一化原因() {
        migrate(null);
        var ids = seedTurnWithAction();

        assertThatThrownBy(() -> insertTarget(ids, "FAILED", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_qa_target_failure");
        assertThatThrownBy(() -> insertTarget(ids, "SUCCEEDED", "RETRIEVAL_TIMEOUT"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_qa_target_failure");
    }

    @Test
    void 有副作用工具未确认不得进入执行及之后的状态() {
        // "确认前一律不执行"的数据库表达（PRD §12.7）。
        migrate(null);
        var ids = seedTurnWithAction();

        for (String status : new String[] {"RUNNING", "SUCCEEDED"}) {
            assertThatThrownBy(() -> insertToolCall(ids.turnId(), 0, true, status, null, false))
                    .as("side-effect tool must not reach %s without confirmation", status)
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_qa_tool_confirm");
        }

        assertThatCode(() -> insertToolCall(ids.turnId(), 0, true, "RUNNING", null, true))
                .doesNotThrowAnyException();
    }

    @Test
    void 待确认的有副作用工具不得携带确认时间戳() {
        migrate(null);
        var ids = seedTurnWithAction();

        assertThatThrownBy(() -> insertToolCall(ids.turnId(), 0, true, "PENDING_CONFIRM", null, true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_qa_tool_confirm");
    }

    @Test
    void 只读工具不需要确认时间戳() {
        migrate(null);
        var ids = seedTurnWithAction();

        assertThatCode(() -> insertToolCall(ids.turnId(), 0, false, "SUCCEEDED", null, false))
                .doesNotThrowAnyException();
    }

    @Test
    void 不可用工具不得携带参数或结果() {
        migrate(null);
        var ids = seedTurnWithAction();

        assertThatThrownBy(() -> jdbc.update(
                        """
                        insert into qa_tool_call
                            (id, turn_id, ordinal, tool_name, side_effect, status, arguments_json)
                        values (?, ?, 0, 'refund', false, 'UNAVAILABLE', '{}')
                        """,
                        UUID.randomUUID(),
                        ids.turnId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_qa_tool_unavailable");
    }

    @Test
    void 失败工具必须带归一化失败码() {
        migrate(null);
        var ids = seedTurnWithAction();

        assertThatThrownBy(() -> insertToolCall(ids.turnId(), 0, false, "FAILED", null, false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_qa_tool_failure");
        assertThatCode(() -> insertToolCall(ids.turnId(), 1, false, "FAILED", "TOOL_EXECUTION_TIMEOUT", false))
                .doesNotThrowAnyException();
    }

    // ——— helpers ———

    private record TurnIds(UUID turnId, UUID subQuestionId, UUID actionId) {}

    private TurnIds seedTurnWithAction() {
        UUID conversationId = UUID.randomUUID();
        jdbc.update(
                "insert into qa_conversation (id, title, last_active_at) values (?, '测试对话', now())",
                conversationId);
        UUID turnId = UUID.randomUUID();
        jdbc.update(
                """
                insert into qa_turn
                    (id, conversation_id, client_request_id, user_message_id, status, completed_at)
                values (?, ?, ?, ?, 'ACTIONS_READY', now())
                """,
                turnId,
                conversationId,
                UUID.randomUUID(),
                UUID.randomUUID());
        UUID subQuestionId = UUID.randomUUID();
        jdbc.update(
                """
                insert into qa_subquestion (id, turn_id, ordinal, text, source)
                values (?, ?, 0, '退货需要哪些材料？', 'MODEL')
                """,
                subQuestionId,
                turnId);
        UUID actionId = UUID.randomUUID();
        jdbc.update(
                """
                insert into qa_retrieval_action
                    (id, turn_id, subquestion_id, ordinal, action_type, reason_code)
                values (?, ?, ?, 1, 'GLOBAL_FALLBACK', 'NO_CANDIDATE')
                """,
                actionId,
                turnId,
                subQuestionId);
        return new TurnIds(turnId, subQuestionId, actionId);
    }

    private void insertRetrieval(UUID turnId, String status, boolean rerankApplied, String degradation) {
        jdbc.update(
                """
                insert into qa_turn_retrieval (turn_id, status, rerank_applied, degradation)
                values (?, ?, ?, ?)
                on conflict (turn_id) do update
                    set status = excluded.status,
                        rerank_applied = excluded.rerank_applied,
                        degradation = excluded.degradation
                """,
                turnId,
                status,
                rerankApplied,
                degradation);
    }

    private void insertSource(TurnIds ids, int ordinal, long chunkId) {
        jdbc.update(
                """
                insert into qa_retrieval_source
                    (id, turn_id, subquestion_id, action_id, ordinal, chunk_id, kb_id, doc_id, origin_channel)
                values (?, ?, ?, ?, ?, ?, 1, 1, 'GLOBAL_VECTOR')
                """,
                UUID.randomUUID(),
                ids.turnId(),
                ids.subQuestionId(),
                ids.actionId(),
                ordinal,
                chunkId);
    }

    private void insertTarget(TurnIds ids, String status, String failureCode) {
        jdbc.update(
                """
                insert into qa_retrieval_target (id, turn_id, action_id, kb_id, status, failure_code)
                values (?, ?, ?, 1, ?, ?)
                """,
                UUID.randomUUID(),
                ids.turnId(),
                ids.actionId(),
                status,
                failureCode);
    }

    private void insertToolCall(
            UUID turnId, int ordinal, boolean sideEffect, String status, String failureCode, boolean confirmed) {
        jdbc.update(
                """
                insert into qa_tool_call
                    (id, turn_id, ordinal, tool_name, side_effect, status, failure_code, confirmed_at)
                values (?, ?, ?, 'submit-return-ticket', ?, ?, ?, case when ? then now() else null end)
                """,
                UUID.randomUUID(),
                turnId,
                ordinal,
                sideEffect,
                status,
                failureCode,
                confirmed);
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

    private Set<String> tableNames() {
        return Set.copyOf(jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = ?",
                String.class,
                schema));
    }

    private Set<String> columns(String table) {
        return Set.copyOf(jdbc.queryForList(
                """
                select column_name from information_schema.columns
                where table_schema = ? and table_name = ?
                """,
                String.class,
                schema,
                table));
    }

    private String schemaUrl() {
        return baseUrl + "?currentSchema=" + schema + ",public";
    }

    private static String withoutQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
}
