package com.app.qa.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.qa.model.RetrievalActionType;
import com.app.qa.model.StageOutcome;
import com.app.qa.model.SubQuestionSource;
import com.app.qa.model.TurnStage;
import com.app.qa.model.TurnStatus;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationPersistenceServiceTests {

    private final String baseUrl = withoutQuery(System.getProperty(
            "spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/app"));
    private final String username = System.getProperty("spring.datasource.username", "app");
    private final String password = System.getProperty("spring.datasource.password", "app");

    private String schema;
    private JdbcTemplate jdbc;
    private ConversationPersistenceService service;

    @BeforeAll
    void createSchema() throws SQLException {
        schema = "xrag_qa_conversation_" + UUID.randomUUID().toString().replace("-", "");
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
        service = new ConversationPersistenceService(jdbc, new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void cleanBusinessRows() {
        jdbc.execute("truncate qa_conversation cascade");
        jdbc.execute("truncate qa_intent_tree_revision cascade");
    }

    @AfterAll
    void dropSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void conv01到04_首问原子保存且相同幂等请求只返回原本轮() {
        UUID requestId = UUID.randomUUID();

        var first = service.submitQuestion(null, requestId, "退款材料有哪些？");
        var replay = service.submitQuestion(first.conversationId(), requestId, "退款材料有哪些？");

        assertThat(first.createdConversation()).isTrue();
        assertThat(first.replayed()).isFalse();
        assertThat(first.status()).isEqualTo(TurnStatus.SAVED);
        assertThat(replay.turnId()).isEqualTo(first.turnId());
        assertThat(replay.messageId()).isEqualTo(first.messageId());
        assertThat(replay.replayed()).isTrue();
        assertThat(service.findConversation(first.conversationId())).get()
                .extracting(ConversationPersistenceService.ConversationView::title)
                .isEqualTo("退款材料有哪些？");
        assertThat(service.findMessages(first.conversationId(), null, 50))
                .singleElement()
                .satisfies(message -> {
                    assertThat(message.sequenceNo()).isEqualTo(1);
                    assertThat(message.content()).isEqualTo("退款材料有哪些？");
                });

        assertThatThrownBy(() -> service.submitQuestion(first.conversationId(), requestId, "退款多久到账？"))
                .isInstanceOf(IdempotencyKeyReusedException.class);
        assertThat(service.findMessages(first.conversationId(), null, 50)).hasSize(1);
    }

    @Test
    void conv05到06_单活动本轮和终态CAS不会被重复回调覆盖() {
        var submission = service.submitQuestion(null, UUID.randomUUID(), "问题一");

        assertThatThrownBy(() -> service.submitQuestion(
                        submission.conversationId(), UUID.randomUUID(), "问题二"))
                .isInstanceOf(ConversationBusyException.class);
        assertThat(service.markProcessing(submission.turnId())).isTrue();
        assertThat(service.markProcessing(submission.turnId())).isFalse();
        assertThat(service.completeTurn(submission.turnId(), TurnStatus.ACTIONS_READY, "ACTIONS_MAPPED"))
                .isTrue();
        assertThat(service.completeTurn(submission.turnId(), TurnStatus.FAILED, "LATE_FAILURE"))
                .isFalse();
        assertThat(service.findTurn(submission.turnId())).get()
                .extracting(ConversationPersistenceService.TurnView::status)
                .isEqualTo(TurnStatus.ACTIONS_READY);
    }

    @Test
    void conv09到12_版本CAS分页与逻辑删除对普通读取生效() {
        var first = service.submitQuestion(null, UUID.randomUUID(), "第一问");
        service.markProcessing(first.turnId());
        service.completeTurn(first.turnId(), TurnStatus.ACTIONS_READY, "DONE");

        assertThat(service.renameConversation(first.conversationId(), 0, "新标题")).isTrue();
        assertThat(service.renameConversation(first.conversationId(), 0, "旧版本覆盖")).isFalse();
        var second = service.submitQuestion(first.conversationId(), UUID.randomUUID(), "第二问");
        service.markProcessing(second.turnId());
        service.completeTurn(second.turnId(), TurnStatus.FAILED, "EXPECTED_TEST_FAILURE");

        assertThat(service.findMessages(first.conversationId(), null, 1))
                .singleElement()
                .extracting(ConversationPersistenceService.MessageView::sequenceNo)
                .isEqualTo(2L);
        assertThat(service.findMessages(first.conversationId(), 2L, 10))
                .singleElement()
                .extracting(ConversationPersistenceService.MessageView::sequenceNo)
                .isEqualTo(1L);

        assertThat(service.softDeleteConversation(first.conversationId(), 1)).isTrue();
        assertThat(service.findConversation(first.conversationId())).isEmpty();
        assertThat(service.findMessages(first.conversationId(), null, 50)).isEmpty();
        assertThat(service.findTurn(first.turnId())).isEmpty();
    }

    @Test
    void db04到05_并发相同幂等键只产生一套业务身份() throws Exception {
        var initial = service.submitQuestion(null, UUID.randomUUID(), "准备对话");
        service.markProcessing(initial.turnId());
        service.completeTurn(initial.turnId(), TurnStatus.ACTIONS_READY, "DONE");
        UUID requestId = UUID.randomUUID();

        var callers = Executors.newFixedThreadPool(2);
        try {
            var first = callers.submit(() -> service.submitQuestion(
                    initial.conversationId(), requestId, "并发问题"));
            var second = callers.submit(() -> service.submitQuestion(
                    initial.conversationId(), requestId, "并发问题"));

            var left = first.get();
            var right = second.get();
            assertThat(left.turnId()).isEqualTo(right.turnId());
            assertThat(left.messageId()).isEqualTo(right.messageId());
            assertThat(service.findMessages(initial.conversationId(), null, 50)).hasSize(2);
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void conv11_同时间戳列表游标以UUID稳定翻页() {
        var left = completedConversation("左侧");
        var right = completedConversation("右侧");
        OffsetDateTime sameActivity = OffsetDateTime.parse("2026-08-07T12:00:00+08:00");
        jdbc.update("update qa_conversation set last_active_at = ?", sameActivity);

        var completeOrder = service.findConversations(null, null, 2);
        var firstPage = service.findConversations(null, null, 1);
        var secondPage = service.findConversations(
                firstPage.get(0).lastActiveAt(), firstPage.get(0).conversationId(), 1);

        assertThat(firstPage).hasSize(1);
        assertThat(secondPage).hasSize(1);
        assertThat(firstPage.get(0).conversationId()).isNotEqualTo(secondPage.get(0).conversationId());
        assertThat(firstPage.get(0).conversationId()).isEqualTo(completeOrder.get(0).conversationId());
        assertThat(secondPage.get(0).conversationId()).isEqualTo(completeOrder.get(1).conversationId());
        assertThat(firstPage.get(0).conversationId()).isIn(left, right);
        assertThat(secondPage.get(0).conversationId()).isIn(left, right);
    }

    @Test
    void db06到07_摘要CAS只安装真实且前移的消息水位() {
        var first = service.submitQuestion(null, UUID.randomUUID(), "第一轮");
        service.markProcessing(first.turnId());
        service.completeTurn(first.turnId(), TurnStatus.ACTIONS_READY, "DONE");
        var second = service.submitQuestion(first.conversationId(), UUID.randomUUID(), "第二轮");
        service.markProcessing(second.turnId());
        service.completeTurn(second.turnId(), TurnStatus.ACTIONS_READY, "DONE");

        assertThat(service.installSummary(
                        first.conversationId(), 0, first.messageId(), 1, 1, "第一版摘要"))
                .get()
                .extracting(ConversationPersistenceService.SummaryView::revision)
                .isEqualTo(1L);
        assertThat(service.installSummary(
                        first.conversationId(), 0, second.messageId(), 2, 2, "过期安装"))
                .isEmpty();
        assertThat(service.installSummary(
                        first.conversationId(), 1, first.messageId(), 1, 2, "水位未前移"))
                .isEmpty();
        assertThat(service.installSummary(
                        first.conversationId(), 1, second.messageId(), 99, 2, "伪造序号"))
                .isEmpty();

        var installed = service.installSummary(
                first.conversationId(), 1, second.messageId(), 2, 2, "第二版摘要");
        assertThat(installed).get().satisfies(summary -> {
            assertThat(summary.revision()).isEqualTo(2);
            assertThat(summary.watermarkMessageId()).isEqualTo(second.messageId());
            assertThat(summary.watermarkSequenceNo()).isEqualTo(2);
        });
        assertThat(service.findCurrentSummary(first.conversationId())).isEqualTo(installed);
        assertThat(service.findSummaryHistory(first.conversationId())).hasSize(2);
    }

    @Test
    void db09_理解结果与终态在同一事务聚合且选项动作互斥() {
        UUID revisionId = UUID.randomUUID();
        jdbc.update("""
                insert into qa_intent_tree_revision
                    (id, revision_no, change_type, node_count, enabled_leaf_count)
                values (?, 1, 'CREATE', 0, 0)
                """, revisionId);
        var clarification = service.submitQuestion(null, UUID.randomUUID(), "我要退货");
        service.markProcessing(clarification.turnId());
        var subQuestion = new ConversationPersistenceService.SubQuestionWrite(
                UUID.randomUUID(), 0, "我要退货", SubQuestionSource.MODEL);
        var options = List.of(
                new ConversationPersistenceService.ClarificationOptionWrite(
                        UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(), "退款政策"),
                new ConversationPersistenceService.ClarificationOptionWrite(
                        UUID.randomUUID(), 2, UUID.randomUUID(), UUID.randomUUID(), "换货政策"));

        assertThat(service.completeUnderstanding(
                        clarification.turnId(),
                        revisionId,
                        TurnStatus.WAITING_CLARIFICATION,
                        "AMBIGUOUS",
                        List.of(subQuestion),
                        options,
                        List.of()))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from qa_clarification_option where turn_id = ? and deleted = false",
                        Integer.class,
                        clarification.turnId()))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject(
                        "select count(*) from qa_retrieval_action where turn_id = ? and deleted = false",
                        Integer.class,
                        clarification.turnId()))
                .isZero();

        var ready = service.submitQuestion(clarification.conversationId(), UUID.randomUUID(), "退款时效");
        service.markProcessing(ready.turnId());
        UUID readySubQuestionId = UUID.randomUUID();
        var action = new ConversationPersistenceService.ActionWrite(
                UUID.randomUUID(), readySubQuestionId, 1, RetrievalActionType.GLOBAL_FALLBACK,
                null, null, null, "LOW_CONFIDENCE");
        assertThat(service.completeUnderstanding(
                        ready.turnId(),
                        revisionId,
                        TurnStatus.ACTIONS_READY,
                        "ACTIONS_MAPPED",
                        List.of(new ConversationPersistenceService.SubQuestionWrite(
                                readySubQuestionId, 0, "退款时效", SubQuestionSource.MODEL)),
                        List.of(),
                        List.of(action)))
                .isTrue();
        assertThat(jdbc.queryForObject(
                        "select count(*) from qa_retrieval_action where turn_id = ? and deleted = false",
                        Integer.class,
                        ready.turnId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "select count(*) from qa_clarification_option where turn_id = ? and deleted = false",
                        Integer.class,
                        ready.turnId()))
                .isZero();
    }

    @Test
    void amb09到10_澄清选择校验同对话来源选项与时效并由服务端生成消息() {
        UUID revisionId = UUID.randomUUID();
        jdbc.update("""
                insert into qa_intent_tree_revision
                    (id, revision_no, change_type, node_count, enabled_leaf_count)
                values (?, 1, 'CREATE', 0, 0)
                """, revisionId);
        UUID nodeA = UUID.randomUUID();
        UUID nodeB = UUID.randomUUID();
        var source = waitingClarification(revisionId, nodeA, nodeB);

        assertThat(service.findMessages(source.conversationId(), null, 50))
                .filteredOn(message -> message.type() == com.app.qa.model.MessageType.CLARIFICATION_GUIDANCE)
                .singleElement()
                .extracting(ConversationPersistenceService.MessageView::content)
                .isEqualTo("请确认你想了解的方向：退货政策；退款政策");

        assertThatThrownBy(() -> service.submitClarificationChoice(
                        source.conversationId(), UUID.randomUUID(), source.turnId(), UUID.randomUUID(), false))
                .isInstanceOf(InvalidClarificationChoiceException.class);

        var otherConversation = service.submitQuestion(null, UUID.randomUUID(), "另一个对话");
        assertThatThrownBy(() -> service.submitClarificationChoice(
                        otherConversation.conversationId(),
                        UUID.randomUUID(),
                        source.turnId(),
                        nodeA,
                        false))
                .isInstanceOf(InvalidClarificationChoiceException.class);
        service.markProcessing(otherConversation.turnId());
        service.completeTurn(otherConversation.turnId(), TurnStatus.FAILED, "TEST_CLEANUP");

        UUID requestId = UUID.randomUUID();
        var selected = service.submitClarificationChoice(
                source.conversationId(), requestId, source.turnId(), nodeB, false);
        var replay = service.submitClarificationChoice(
                source.conversationId(), requestId, source.turnId(), nodeB, false);

        assertThat(replay.turnId()).isEqualTo(selected.turnId());
        assertThat(replay.replayed()).isTrue();
        assertThat(jdbc.queryForMap("""
                select t.source_turn_id, t.selected_intent_node_id, m.message_type, m.content
                  from qa_turn t join qa_message m on m.id = t.user_message_id
                 where t.id = ?
                """, selected.turnId()))
                .containsEntry("source_turn_id", source.turnId())
                .containsEntry("selected_intent_node_id", nodeB)
                .containsEntry("message_type", "CLARIFICATION_CHOICE")
                .containsEntry("content", "我想了解：退款政策");

        service.markProcessing(selected.turnId());
        service.completeTurn(selected.turnId(), TurnStatus.FAILED, "TEST_CLEANUP");
        assertThatThrownBy(() -> service.submitClarificationChoice(
                        source.conversationId(), UUID.randomUUID(), source.turnId(), nodeA, false))
                .isInstanceOf(InvalidClarificationChoiceException.class);

        var noneSource = waitingClarification(revisionId, UUID.randomUUID(), UUID.randomUUID());
        var none = service.submitClarificationChoice(
                noneSource.conversationId(), UUID.randomUUID(), noneSource.turnId(), null, true);
        assertThat(jdbc.queryForMap("""
                select t.source_turn_id, t.selected_intent_node_id, m.message_type, m.content
                  from qa_turn t join qa_message m on m.id = t.user_message_id
                 where t.id = ?
                """, none.turnId()))
                .containsEntry("source_turn_id", noneSource.turnId())
                .containsEntry("selected_intent_node_id", null)
                .containsEntry("message_type", "CLARIFICATION_CHOICE")
                .containsEntry("content", "都不是这些，请补充其他方向。");
    }

    @Test
    void conv07_恢复只收敛过期Processing且不触碰新本轮() {
        var stale = service.submitQuestion(null, UUID.randomUUID(), "过期本轮");
        service.markProcessing(stale.turnId());
        jdbc.update("update qa_turn set started_at = now() - interval '10 minutes' where id = ?", stale.turnId());
        var recent = service.submitQuestion(null, UUID.randomUUID(), "新本轮");
        service.markProcessing(recent.turnId());

        assertThat(service.failStaleProcessing(Duration.ofMinutes(3))).isEqualTo(1);
        assertThat(service.findTurn(stale.turnId())).get().satisfies(turn -> {
            assertThat(turn.status()).isEqualTo(TurnStatus.FAILED);
            assertThat(turn.terminalReason()).isEqualTo("PROCESS_INTERRUPTED");
        });
        assertThat(service.findTurn(recent.turnId())).get()
                .extracting(ConversationPersistenceService.TurnView::status)
                .isEqualTo(TurnStatus.PROCESSING);
    }

    @Test
    void int03_同一评分工作单元至多记录一个无正文stage() {
        var submission = service.submitQuestion(null, UUID.randomUUID(), "评分问题");
        UUID revisionId = UUID.randomUUID();
        jdbc.update("""
                insert into qa_intent_tree_revision
                    (id, revision_no, change_type, node_count, enabled_leaf_count)
                values (?, 1, 'CREATE', 0, 0)
                """, revisionId);
        var stages = new JdbcTurnStageSink(jdbc);
        OffsetDateTime startedAt = OffsetDateTime.now();
        UUID firstCall = UUID.randomUUID();

        stages.record(new TurnStageSink.StageEvent(
                submission.turnId(), TurnStage.INTENT_SCORE, 0, StageOutcome.SUCCEEDED,
                null, firstCall, revisionId, startedAt, startedAt.plusNanos(1_000_000)));
        stages.record(new TurnStageSink.StageEvent(
                submission.turnId(), TurnStage.INTENT_SCORE, 0, StageOutcome.DEGRADED,
                "LATE_DUPLICATE", UUID.randomUUID(), revisionId,
                startedAt, startedAt.plusNanos(2_000_000)));

        assertThat(jdbc.queryForMap(
                        "select stage, outcome, logical_call_id, intent_revision_id, duration_ms "
                                + "from qa_turn_stage where turn_id = ?",
                        submission.turnId()))
                .containsEntry("stage", "INTENT_SCORE")
                .containsEntry("outcome", "SUCCEEDED")
                .containsEntry("logical_call_id", firstCall)
                .containsEntry("intent_revision_id", revisionId)
                .containsEntry("duration_ms", 1L);
    }

    private UUID completedConversation(String question) {
        var submission = service.submitQuestion(null, UUID.randomUUID(), question);
        service.markProcessing(submission.turnId());
        service.completeTurn(submission.turnId(), TurnStatus.ACTIONS_READY, "DONE");
        return submission.conversationId();
    }

    private ConversationPersistenceService.TurnSubmission waitingClarification(
            UUID revisionId, UUID nodeA, UUID nodeB) {
        var source = service.submitQuestion(null, UUID.randomUUID(), "我要退货");
        service.markProcessing(source.turnId());
        assertThat(service.completeUnderstanding(
                        source.turnId(),
                        revisionId,
                        TurnStatus.WAITING_CLARIFICATION,
                        "AMBIGUOUS",
                        List.of(new ConversationPersistenceService.SubQuestionWrite(
                                UUID.randomUUID(), 0, "我要退货", SubQuestionSource.MODEL)),
                        List.of(
                                new ConversationPersistenceService.ClarificationOptionWrite(
                                        UUID.randomUUID(), 1, nodeA, UUID.randomUUID(), "退货政策"),
                                new ConversationPersistenceService.ClarificationOptionWrite(
                                        UUID.randomUUID(), 2, nodeB, UUID.randomUUID(), "退款政策")),
                        List.of()))
                .isTrue();
        return source;
    }

    private String schemaUrl() {
        return baseUrl + "?currentSchema=" + schema + ",public";
    }

    private static String withoutQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }
}
