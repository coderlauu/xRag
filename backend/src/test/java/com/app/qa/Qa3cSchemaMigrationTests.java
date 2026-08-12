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

/** 3C-DB-01~03：V11 升级路径与数据库不变量。 */
class Qa3cSchemaMigrationTests {

    private final String baseUrl = withoutQuery(
            System.getProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/app"));
    private final String username = System.getProperty("spring.datasource.username", "app");
    private final String password = System.getProperty("spring.datasource.password", "app");

    private String schema;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createIsolatedSchema() throws SQLException {
        schema = "xrag_qa3c_" + UUID.randomUUID().toString().replace("-", "");
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
    void db01_从V10升级到V11保留旧数据并创建任务表和扩展闭集() {
        migrate("10");
        var ids = seedTurnAndUserMessage();

        migrate(null);

        assertThat(jdbc.queryForObject(
                        "select content from qa_message where id = ?", String.class, ids.userMessageId()))
                .isEqualTo("旧问题");
        assertThat(tableNames()).contains("qa_generation_task");
        assertThat(columns("qa_message")).contains("incomplete");
        assertThat(constraint("qa_turn", "ck_qa_turn_status"))
                .contains("QUEUED", "GENERATING", "COMPLETED", "STOPPED", "TIMED_OUT");
        assertThat(constraint("qa_turn_stage", "ck_qa_stage_name"))
                .contains("PROMPT", "QUEUE", "GENERATE", "PERSIST");
        assertThat(indexNames("qa_message")).contains("uq_qa_message_assistant_answer");
        migrate(null);
    }

    @Test
    void db01_空库可直接迁移到V11() {
        migrate(null);

        assertThat(tableNames()).contains("qa_generation_task", "qa_message", "qa_turn");
        assertThat(jdbc.queryForObject(
                        "select max(version::integer) from flyway_schema_history where success", Integer.class))
                .isEqualTo(11);
    }

    @Test
    void db02_同一轮只能有一个任务和一个未删除助手答案() {
        migrate(null);
        var ids = seedTurnAndUserMessage();
        insertTask(ids, "QUEUED", null, null, null);

        assertThatThrownBy(() -> insertTask(ids, "QUEUED", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("qa_generation_task_turn_id_key");

        insertAssistantAnswer(ids, 2, false);
        assertThatThrownBy(() -> insertAssistantAnswer(ids, 3, false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_qa_message_assistant_answer");
    }

    @Test
    void db03_非法状态时间和保存组合由数据库拒绝() {
        migrate(null);
        var ids = seedTurnAndUserMessage();

        assertThatThrownBy(() -> insertTask(ids, "QUEUED", "UNEXPECTED", java.time.OffsetDateTime.now(), null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_qa_generation_terminal");
        assertThatThrownBy(() -> insertTask(ids, "COMPLETED", null, java.time.OffsetDateTime.now(), true))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_qa_generation_saved");
        assertThatThrownBy(() -> insertTask(ids, "TIMED_OUT", null, java.time.OffsetDateTime.now(), false))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_qa_generation_saved");
        assertThatThrownBy(() -> jdbc.update("update qa_message set incomplete = true where id = ?", ids.userMessageId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_qa_message_incomplete");

        assertThatCode(() -> jdbc.update(
                        "update qa_turn set status = 'QUEUED', completed_at = null where id = ?", ids.turnId()))
                .doesNotThrowAnyException();
    }

    private record SeedIds(UUID conversationId, UUID turnId, UUID userMessageId) {}

    private SeedIds seedTurnAndUserMessage() {
        var conversationId = UUID.randomUUID();
        var turnId = UUID.randomUUID();
        var userMessageId = UUID.randomUUID();
        jdbc.update(
                "insert into qa_conversation (id, title, last_active_at) values (?, '测试对话', now())",
                conversationId);
        jdbc.update(
                """
                insert into qa_turn
                    (id, conversation_id, client_request_id, user_message_id, status, completed_at)
                values (?, ?, ?, ?, 'ACTIONS_READY', now())
                """,
                turnId,
                conversationId,
                UUID.randomUUID(),
                userMessageId);
        jdbc.update(
                """
                insert into qa_message
                    (id, conversation_id, turn_id, sequence_no, role, message_type, content)
                values (?, ?, ?, 1, 'USER', 'USER_QUERY', '旧问题')
                """,
                userMessageId,
                conversationId,
                turnId);
        return new SeedIds(conversationId, turnId, userMessageId);
    }

    private void insertTask(
            SeedIds ids,
            String status,
            String terminalReason,
            java.time.OffsetDateTime completedAt,
            Boolean answerSaved) {
        jdbc.update(
                """
                insert into qa_generation_task
                    (id, turn_id, conversation_id, status, terminal_reason, queued_at, completed_at, answer_saved)
                values (?, ?, ?, ?, ?, now(), ?, ?)
                """,
                UUID.randomUUID(),
                ids.turnId(),
                ids.conversationId(),
                status,
                terminalReason,
                completedAt,
                answerSaved);
    }

    private void insertAssistantAnswer(SeedIds ids, long sequence, boolean incomplete) {
        jdbc.update(
                """
                insert into qa_message
                    (id, conversation_id, turn_id, sequence_no, role, message_type, content, incomplete)
                values (?, ?, ?, ?, 'ASSISTANT', 'ASSISTANT_ANSWER', '回答', ?)
                """,
                UUID.randomUUID(),
                ids.conversationId(),
                ids.turnId(),
                sequence,
                incomplete);
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

    private String constraint(String table, String name) {
        return jdbc.queryForObject(
                """
                select pg_get_constraintdef(c.oid)
                from pg_constraint c
                join pg_class t on t.oid = c.conrelid
                join pg_namespace n on n.oid = t.relnamespace
                where n.nspname = ? and t.relname = ? and c.conname = ?
                """,
                String.class,
                schema,
                table,
                name);
    }

    private Set<String> tableNames() {
        return Set.copyOf(jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = ?", String.class, schema));
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

    private Set<String> indexNames(String table) {
        return Set.copyOf(jdbc.queryForList(
                "select indexname from pg_indexes where schemaname = ? and tablename = ?",
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
