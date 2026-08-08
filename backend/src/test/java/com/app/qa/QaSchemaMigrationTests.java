package com.app.qa;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;

class QaSchemaMigrationTests {

    private static final Set<String> QA_TABLES = Set.of(
            "qa_conversation",
            "qa_turn",
            "qa_message",
            "qa_conversation_summary",
            "qa_subquestion",
            "qa_intent_match",
            "qa_clarification_option",
            "qa_retrieval_action",
            "qa_turn_stage",
            "qa_intent_tree_head",
            "qa_intent_tree_revision",
            "qa_intent_node_snapshot");

    private final String baseUrl = withoutQuery(System.getProperty(
            "spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/app"));
    private final String username = System.getProperty("spring.datasource.username", "app");
    private final String password = System.getProperty("spring.datasource.password", "app");

    private String schema;
    private JdbcTemplate jdbc;

    @BeforeEach
    void createIsolatedSchema() throws SQLException {
        schema = "xrag_qa_" + UUID.randomUUID().toString().replace("-", "");
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
    void db01_空Schema升级创建全部Qa表且已有V6数据不受影响() {
        migrate("6");
        jdbc.update("""
                insert into knowledge_base
                    (name, embedding_model, embedding_dimensions, storage_alias)
                values ('qa-migration-existing', 'text-embedding-v3', 1024, 'qa-migration-existing')
                """);

        migrate(null);

        assertThat(qaTableNames()).containsExactlyInAnyOrderElementsOf(QA_TABLES);
        assertThat(jdbc.queryForObject(
                "select count(*) from knowledge_base where name = 'qa-migration-existing'", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "select count(*) from qa_intent_tree_head where singleton_id = 1", Integer.class))
                .isEqualTo(1);
        migrate(null);
    }

    @Test
    void db02_对话标题版本与逻辑删除不变量由数据库拒绝() {
        migrate(null);

        assertThatThrownBy(() -> jdbc.update("""
                    insert into qa_conversation (id, title, version, last_active_at)
                    values (?, '  ', 0, now())
                    """, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_qa_conversation_title");
        assertThatThrownBy(() -> jdbc.update("""
                    insert into qa_conversation (id, title, version, last_active_at, deleted)
                    values (?, '合法标题', -1, now(), true)
                    """, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void db03_同一对话只能有一个活动本轮() {
        migrate(null);
        UUID conversationId = insertConversation();
        insertTurn(conversationId, "SAVED");

        assertThatThrownBy(() -> insertTurn(conversationId, "PROCESSING"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_qa_turn_one_active");
    }

    @Test
    void db06_同一对话只能有一个当前摘要() {
        migrate(null);
        UUID conversationId = insertConversation();
        UUID turnId = insertTurn(conversationId, "ACTIONS_READY");
        UUID messageId = UUID.randomUUID();
        jdbc.update("""
                insert into qa_message
                    (id, conversation_id, turn_id, sequence_no, role, message_type, content)
                values (?, ?, ?, 1, 'USER', 'USER_QUERY', '问题')
                """, messageId, conversationId, turnId);
        insertSummary(conversationId, messageId, 1);

        assertThatThrownBy(() -> insertSummary(conversationId, messageId, 2))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uq_qa_summary_current");
    }

    @Test
    void db08_子问题序号与匹配分数目标由数据库约束() {
        migrate(null);
        UUID revisionId = insertRevision();
        UUID conversationId = insertConversation();
        UUID turnId = insertTurn(conversationId, "ACTIONS_READY");

        assertThatThrownBy(() -> jdbc.update("""
                    insert into qa_subquestion (id, turn_id, ordinal, text, source)
                    values (?, ?, 3, '第四个问题', 'MODEL')
                    """, UUID.randomUUID(), turnId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_qa_subquestion_ordinal");

        UUID subQuestionId = UUID.randomUUID();
        jdbc.update("""
                insert into qa_subquestion (id, turn_id, ordinal, text, source)
                values (?, ?, 0, '问题', 'MODEL')
                """, subQuestionId, turnId);
        assertThatThrownBy(() -> jdbc.update("""
                    insert into qa_intent_match
                        (id, turn_id, subquestion_id, intent_revision_id, node_id, node_type,
                         full_path_snapshot, system_action, score, rank, category_node_id)
                    values (?, ?, ?, ?, ?, 'SYSTEM', '系统 › 身份', 'ASSISTANT_IDENTITY',
                            'NaN'::double precision, 1, ?)
                    """, UUID.randomUUID(), turnId, subQuestionId, revisionId,
                        UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_qa_intent_match_score");
    }

    @Test
    void db12_阶段表没有正文列且拒绝非法耗时与序号() {
        migrate(null);
        assertThat(columns("qa_turn_stage"))
                .noneMatch(column -> column.matches(".*(prompt|message|content|payload|response|error_body).*"));
        UUID conversationId = insertConversation();
        UUID turnId = insertTurn(conversationId, "ACTIONS_READY");

        assertThatThrownBy(() -> jdbc.update("""
                    insert into qa_turn_stage
                        (id, turn_id, stage, work_unit_ordinal, outcome,
                         started_at, completed_at, duration_ms)
                    values (?, ?, 'INTENT_SCORE', 4, 'FAILED', now(), now(), -1)
                    """, UUID.randomUUID(), turnId))
                .isInstanceOf(DataIntegrityViolationException.class);
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

    private Set<String> qaTableNames() {
        return Set.copyOf(jdbc.queryForList("""
                select table_name
                from information_schema.tables
                where table_schema = ? and table_name like 'qa\\_%' escape '\\'
                """, String.class, schema));
    }

    private Set<String> columns(String table) {
        return Set.copyOf(jdbc.queryForList("""
                select column_name
                from information_schema.columns
                where table_schema = ? and table_name = ?
                """, String.class, schema, table));
    }

    private UUID insertConversation() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into qa_conversation (id, title, last_active_at)
                values (?, '测试对话', now())
                """, id);
        return id;
    }

    private UUID insertTurn(UUID conversationId, String status) {
        UUID id = UUID.randomUUID();
        boolean terminal = !Set.of("SAVED", "PROCESSING").contains(status);
        jdbc.update("""
                insert into qa_turn
                    (id, conversation_id, client_request_id, user_message_id, status, completed_at)
                values (?, ?, ?, ?, ?, case when ? then now() else null end)
                """, id, conversationId, UUID.randomUUID(), UUID.randomUUID(), status, terminal);
        return id;
    }

    private UUID insertRevision() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into qa_intent_tree_revision
                    (id, revision_no, change_type, node_count, enabled_leaf_count)
                values (?, 1, 'CREATE', 0, 0)
                """, id);
        return id;
    }

    private void insertSummary(UUID conversationId, UUID messageId, long revision) {
        jdbc.update("""
                insert into qa_conversation_summary
                    (id, conversation_id, revision, content, watermark_message_id,
                     watermark_sequence_no, covered_turn_count)
                values (?, ?, ?, '摘要', ?, 1, 1)
                """, UUID.randomUUID(), conversationId, revision, messageId);
    }

    private String schemaUrl() {
        return baseUrl + "?currentSchema=" + schema + ",public";
    }

    private static String withoutQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
}
