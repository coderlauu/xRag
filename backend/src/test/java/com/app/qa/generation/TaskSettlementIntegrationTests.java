package com.app.qa.generation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 3C-DB-04~07、3C-SET-01~03：真实事务上的唯一结算。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TaskSettlementIntegrationTests {

    private final String baseUrl = withoutQuery(
            System.getProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/app"));
    private final String username = System.getProperty("spring.datasource.username", "app");
    private final String password = System.getProperty("spring.datasource.password", "app");

    private String schema;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;

    @BeforeAll
    void createSchema() throws SQLException {
        schema = "xrag_qa_settlement_" + UUID.randomUUID().toString().replace("-", "");
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
        transactionManager = new DataSourceTransactionManager(dataSource);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("truncate qa_conversation cascade");
    }

    @AfterAll
    void dropSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void db04_完整回答成功时TaskTurnMessage在同一事务一致() {
        var seed = seedGeneratingTask();
        var fixture = settlement(seed, new JdbcAssistantAnswerStore(jdbc));

        var outcome = fixture.settlement.trySettle(TaskSettlement.Request.completed("完整回答"));

        assertThat(outcome.won()).isTrue();
        assertThat(outcome.status()).isEqualTo(GenerationTaskStatus.COMPLETED);
        assertThat(outcome.saved()).isTrue();
        assertThat(outcome.assistantMessageId()).isNotNull();
        assertThat(jdbc.queryForMap("select status, answer_saved, assistant_message_id from qa_generation_task where id = ?", seed.taskId.value()))
                .containsEntry("status", "COMPLETED")
                .containsEntry("answer_saved", true)
                .containsEntry("assistant_message_id", outcome.assistantMessageId());
        assertThat(jdbc.queryForMap("select status, terminal_reason from qa_turn where id = ?", seed.turnId))
                .containsEntry("status", "COMPLETED")
                .containsEntry("terminal_reason", "COMPLETED");
        assertThat(jdbc.queryForMap("select content, incomplete from qa_message where id = ?", outcome.assistantMessageId()))
                .containsEntry("content", "完整回答")
                .containsEntry("incomplete", false);
        assertThat(fixture.releases).hasValue(1);
        assertThat(fixture.events).containsExactly(outcome);
    }

    @Test
    void db05_完整回答保存失败仍结算Completed且不暴露异常() {
        var seed = seedGeneratingTask();
        AssistantAnswerStore failingStore = answer -> {
            throw new DataAccessResourceFailureException("secret-db-host:5432/key=private");
        };
        var fixture = settlement(seed, failingStore);

        var outcome = fixture.settlement.trySettle(TaskSettlement.Request.completed("已完整流出"));

        assertThat(outcome.won()).isTrue();
        assertThat(outcome.status()).isEqualTo(GenerationTaskStatus.COMPLETED);
        assertThat(outcome.saved()).isFalse();
        assertThat(outcome.assistantMessageId()).isNull();
        assertThat(jdbc.queryForMap("select terminal_reason, answer_saved, assistant_message_id from qa_generation_task where id = ?", seed.taskId.value()))
                .containsEntry("terminal_reason", "COMPLETED_UNSAVED")
                .containsEntry("answer_saved", false)
                .containsEntry("assistant_message_id", null);
        assertThat(jdbc.queryForObject("select count(*) from qa_message where turn_id = ? and message_type = 'ASSISTANT_ANSWER'", Integer.class, seed.turnId))
                .isZero();
        assertThat(outcome.toString()).doesNotContain("secret-db-host", "private");
    }

    @Test
    void db06_停止或失败已有部分时只保存一次Incomplete消息() {
        for (var request : List.of(
                TaskSettlement.Request.stopped("部分回答"),
                TaskSettlement.Request.failed(GenerationFailureCode.MODEL_UNAVAILABLE, "部分回答"))) {
            var seed = seedGeneratingTask();
            var fixture = settlement(seed, new JdbcAssistantAnswerStore(jdbc));

            var first = fixture.settlement.trySettle(request);
            var replay = fixture.settlement.trySettle(request);

            assertThat(first.won()).isTrue();
            assertThat(first.saved()).isTrue();
            assertThat(replay.won()).isFalse();
            assertThat(replay.assistantMessageId()).isEqualTo(first.assistantMessageId());
            assertThat(jdbc.queryForMap("select content, incomplete from qa_message where id = ?", first.assistantMessageId()))
                    .containsEntry("content", "部分回答")
                    .containsEntry("incomplete", true);
            assertThat(jdbc.queryForObject("select count(*) from qa_message where turn_id = ? and message_type = 'ASSISTANT_ANSWER'", Integer.class, seed.turnId))
                    .isEqualTo(1);
            assertThat(fixture.releases).hasValue(1);
            assertThat(fixture.events).hasSize(1);
        }
    }

    @Test
    void db07_零字符停止或失败不创建空消息() {
        for (var request : List.of(
                TaskSettlement.Request.stopped(""),
                TaskSettlement.Request.failed(GenerationFailureCode.MODEL_UNAVAILABLE, ""))) {
            var seed = seedGeneratingTask();
            var fixture = settlement(seed, new JdbcAssistantAnswerStore(jdbc));

            var outcome = fixture.settlement.trySettle(request);

            assertThat(outcome.won()).isTrue();
            assertThat(outcome.saved()).isFalse();
            assertThat(outcome.assistantMessageId()).isNull();
            assertThat(jdbc.queryForObject("select count(*) from qa_message where turn_id = ? and message_type = 'ASSISTANT_ANSWER'", Integer.class, seed.turnId))
                    .isZero();
        }
    }

    @Test
    void set01到03_完成停止失败三方竞态只有一个终态和一次副作用() throws Exception {
        var seed = seedGeneratingTask();
        var fixture = settlement(seed, new JdbcAssistantAnswerStore(jdbc));
        var requests = List.of(
                TaskSettlement.Request.completed("完整"),
                TaskSettlement.Request.stopped("部分"),
                TaskSettlement.Request.failed(GenerationFailureCode.GENERATION_TIMEOUT, "部分"));
        var barrier = new CyclicBarrier(requests.size());
        var outcomes = new CopyOnWriteArrayList<TaskSettlement.Outcome>();
        var executor = Executors.newFixedThreadPool(requests.size());
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (var request : requests) {
                futures.add(executor.submit(() -> {
                    barrier.await();
                    outcomes.add(fixture.settlement.trySettle(request));
                    return null;
                }));
            }
            for (var future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(outcomes).filteredOn(TaskSettlement.Outcome::won).hasSize(1);
        assertThat(outcomes).extracting(TaskSettlement.Outcome::status).containsOnly(outcomes.get(0).status());
        assertThat(fixture.releases).hasValue(1);
        assertThat(fixture.events).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from qa_message where turn_id = ? and message_type = 'ASSISTANT_ANSWER'", Integer.class, seed.turnId))
                .isLessThanOrEqualTo(1);
    }

    private Fixture settlement(Seed seed, AssistantAnswerStore answerStore) {
        var repository = new JdbcTaskSettlementRepository(jdbc, transactionManager, answerStore);
        var releases = new AtomicInteger();
        var events = new CopyOnWriteArrayList<TaskSettlement.Outcome>();
        return new Fixture(new TaskSettlement(seed.taskId, repository, releases::incrementAndGet, events::add), releases, events);
    }

    private Seed seedGeneratingTask() {
        var conversationId = UUID.randomUUID();
        var turnId = UUID.randomUUID();
        var userMessageId = UUID.randomUUID();
        var taskId = new GenerationTaskId(UUID.randomUUID());
        jdbc.update("insert into qa_conversation (id, title, last_active_at) values (?, '测试对话', now())", conversationId);
        jdbc.update("""
                insert into qa_turn
                    (id, conversation_id, client_request_id, user_message_id, status, started_at)
                values (?, ?, ?, ?, 'GENERATING', now())
                """, turnId, conversationId, UUID.randomUUID(), userMessageId);
        jdbc.update("""
                insert into qa_message
                    (id, conversation_id, turn_id, sequence_no, role, message_type, content)
                values (?, ?, ?, 1, 'USER', 'USER_QUERY', '问题')
                """, userMessageId, conversationId, turnId);
        jdbc.update("""
                insert into qa_generation_task
                    (id, turn_id, conversation_id, status, queued_at, generation_started_at)
                values (?, ?, ?, 'GENERATING', now(), now())
                """, taskId.value(), turnId, conversationId);
        return new Seed(taskId, turnId);
    }

    private String schemaUrl() {
        return baseUrl + "?currentSchema=" + schema + ",public";
    }

    private static String withoutQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private record Seed(GenerationTaskId taskId, UUID turnId) {}

    private record Fixture(
            TaskSettlement settlement,
            AtomicInteger releases,
            List<TaskSettlement.Outcome> events) {}
}
