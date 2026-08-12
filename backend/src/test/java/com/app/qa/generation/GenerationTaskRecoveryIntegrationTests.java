package com.app.qa.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GenerationTaskRecoveryIntegrationTests {

    private final String baseUrl = withoutQuery(
            System.getProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/app"));
    private final String username = System.getProperty("spring.datasource.username", "app");
    private final String password = System.getProperty("spring.datasource.password", "app");

    private String schema;
    private JdbcTemplate jdbc;
    private GenerationTaskRecovery recovery;

    @BeforeAll
    void createSchema() throws SQLException {
        schema = "xrag_qa_recovery_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        var dataSource = new DriverManagerDataSource(schemaUrl(), username, password);
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        jdbc = new JdbcTemplate(dataSource);
        recovery = new GenerationTaskRecovery(jdbc, new DataSourceTransactionManager(dataSource));
    }

    @AfterAll
    void dropSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void 重启将排队和生成中任务原子恢复为失败且可重复执行() {
        jdbc.execute("truncate qa_conversation cascade");
        UUID queued = seedTask("QUEUED");
        UUID generating = seedTask("GENERATING");
        UUID stopped = seedTask("STOPPED");

        recovery.recoverAfterRestart();
        recovery.recoverAfterRestart();

        assertRecovered(queued);
        assertRecovered(generating);
        assertThat(jdbc.queryForMap("select status, terminal_reason from qa_generation_task where id = ?", stopped))
                .containsEntry("status", "STOPPED")
                .containsEntry("terminal_reason", "STOPPED");
    }

    private UUID seedTask(String status) {
        UUID conversationId = UUID.randomUUID();
        UUID turnId = UUID.randomUUID();
        UUID userMessageId = UUID.randomUUID();
        UUID taskId = UUID.randomUUID();
        boolean terminal = status.equals("STOPPED");
        jdbc.update("insert into qa_conversation (id, title, last_active_at) values (?, '恢复测试', now())", conversationId);
        jdbc.update("""
                insert into qa_turn
                    (id, conversation_id, client_request_id, user_message_id, status, terminal_reason, completed_at)
                values (?, ?, ?, ?, ?, ?, case when ? then now() else null end)
                """, turnId, conversationId, UUID.randomUUID(), userMessageId, status,
                terminal ? "STOPPED" : null, terminal);
        jdbc.update("""
                insert into qa_message
                    (id, conversation_id, turn_id, sequence_no, role, message_type, content)
                values (?, ?, ?, 1, 'USER', 'USER_QUERY', '问题')
                """, userMessageId, conversationId, turnId);
        jdbc.update("""
                insert into qa_generation_task
                    (id, conversation_id, turn_id, status, terminal_reason,
                     answer_saved, completed_at)
                values (?, ?, ?, ?, ?, ?, case when ? then now() else null end)
                """, taskId, conversationId, turnId, status,
                terminal ? "STOPPED" : null, terminal ? false : null, terminal);
        return taskId;
    }

    private void assertRecovered(UUID taskId) {
        var task = jdbc.queryForMap("""
                select t.status, t.terminal_reason, t.answer_saved, t.completed_at,
                       q.status as turn_status, q.terminal_reason as turn_reason, q.completed_at as turn_completed_at
                  from qa_generation_task t join qa_turn q on q.id = t.turn_id
                 where t.id = ?
                """, taskId);
        assertThat(task)
                .containsEntry("status", "FAILED")
                .containsEntry("terminal_reason", "SERVICE_RESTARTED")
                .containsEntry("answer_saved", false)
                .containsEntry("turn_status", "FAILED")
                .containsEntry("turn_reason", "SERVICE_RESTARTED");
        assertThat(task.get("completed_at")).isNotNull();
        assertThat(task.get("turn_completed_at")).isNotNull();
    }

    private String schemaUrl() {
        return baseUrl + "?currentSchema=" + schema + ",public";
    }

    private static String withoutQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
}
