package com.app.qa.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.model.AssistantMessage;
import com.app.ai.model.ChatClient;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.ChatResult;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ExecutionInfo;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ProviderId;
import com.app.ai.model.RequestId;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.TokenUsage;
import com.app.ai.model.UserMessage;
import com.app.qa.config.QaUnderstandingProperties;
import com.app.qa.conversation.ConversationPersistenceService;
import com.app.qa.conversation.JdbcTurnStageSink;
import com.app.qa.model.TurnStatus;
import com.app.qa.prompt.PromptTemplateCatalog;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConversationMemoryServiceTests {

    private final String baseUrl = withoutQuery(System.getProperty(
            "spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/app"));
    private final String username = System.getProperty("spring.datasource.username", "app");
    private final String password = System.getProperty("spring.datasource.password", "app");

    private String schema;
    private JdbcTemplate jdbc;
    private ConversationPersistenceService conversations;
    private FakeChat chat;
    private ConversationMemoryService memory;

    @BeforeAll
    void createSchema() throws SQLException {
        schema = "xrag_qa_memory_" + UUID.randomUUID().toString().replace("-", "");
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
        conversations = new ConversationPersistenceService(
                jdbc, new DataSourceTransactionManager(dataSource));
    }

    @BeforeEach
    void reset() {
        jdbc.execute("truncate qa_conversation cascade");
        chat = new FakeChat();
        memory = new ConversationMemoryService(
                jdbc,
                conversations,
                new QaUnderstandingProperties(),
                PromptTemplateCatalog.fromClasspath(65_536),
                chat,
                new JdbcTurnStageSink(jdbc));
    }

    @AfterAll
    void dropSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void mem01到05_只加载水位后完整轮且未达十二轮不调用摘要() {
        UUID conversationId = complete(null, "完整轮", TurnStatus.ACTIONS_READY).conversationId();
        complete(conversationId, "失败半轮", TurnStatus.FAILED);
        complete(conversationId, "未解决歧义", TurnStatus.WAITING_CLARIFICATION);
        var current = conversations.submitQuestion(conversationId, UUID.randomUUID(), "当前问题");

        var snapshot = memory.prepare(current.turnId());

        assertThat(snapshot.summaryUsed()).isFalse();
        assertThat(snapshot.recentTurns()).hasSize(1);
        assertThat(snapshot.recentTurns().get(0).messages())
                .extracting(ConversationMemoryService.MemoryMessage::content)
                .containsExactly("完整轮");
        assertThat(snapshot.currentMessage().content()).isEqualTo("当前问题");
        assertThat(chat.commands).isEmpty();
        assertThat(jdbc.queryForObject(
                        "select outcome from qa_turn_stage where turn_id = ? and stage = 'SUMMARY'",
                        String.class,
                        current.turnId()))
                .isEqualTo("SKIPPED");
    }

    @Test
    void mem06到07_十二轮只压缩滑出窗口并用Balanced增量合并旧摘要() {
        UUID conversationId = null;
        for (int index = 1; index <= 12; index++) {
            conversationId = complete(conversationId, "轮次-" + index, TurnStatus.ACTIONS_READY)
                    .conversationId();
        }
        var current = conversations.submitQuestion(conversationId, UUID.randomUUID(), "当前-一");

        var first = memory.prepare(current.turnId());

        assertThat(first.summary()).isEqualTo("summary-1");
        assertThat(first.recentTurns()).hasSize(8);
        assertThat(flatten(first.recentTurns())).containsExactly(
                "轮次-5", "轮次-6", "轮次-7", "轮次-8",
                "轮次-9", "轮次-10", "轮次-11", "轮次-12");
        assertThat(chat.commands).hasSize(1);
        assertThat(chat.commands.get(0).tier()).isEqualTo(ServiceTier.BALANCED);
        String firstPrompt = ((UserMessage) chat.commands.get(0).messages().get(1)).text();
        assertThat(firstPrompt).contains("轮次-1", "轮次-4").doesNotContain("轮次-5");
        assertThat(conversations.findCurrentSummary(conversationId)).get().satisfies(summary -> {
            assertThat(summary.watermarkSequenceNo()).isEqualTo(4);
            assertThat(summary.coveredTurnCount()).isEqualTo(4);
        });

        conversations.markProcessing(current.turnId());
        conversations.completeTurn(current.turnId(), TurnStatus.ACTIONS_READY, "DONE");
        for (int index = 14; index <= 16; index++) {
            complete(conversationId, "轮次-" + index, TurnStatus.ACTIONS_READY);
        }
        var nextCurrent = conversations.submitQuestion(conversationId, UUID.randomUUID(), "当前-二");

        var second = memory.prepare(nextCurrent.turnId());

        assertThat(second.summary()).isEqualTo("summary-2");
        assertThat(flatten(second.recentTurns())).containsExactly(
                "轮次-9", "轮次-10", "轮次-11", "轮次-12",
                "当前-一", "轮次-14", "轮次-15", "轮次-16");
        String secondPrompt = ((UserMessage) chat.commands.get(1).messages().get(1)).text();
        assertThat(secondPrompt)
                .contains("summary-1", "轮次-5", "轮次-8")
                .doesNotContain("轮次-9");
        assertThat(conversations.findCurrentSummary(conversationId)).get().satisfies(summary -> {
            assertThat(summary.watermarkSequenceNo()).isEqualTo(8);
            assertThat(summary.coveredTurnCount()).isEqualTo(8);
        });
        assertThat(jdbc.queryForObject(
                        "select logical_call_id is not null from qa_turn_stage "
                                + "where turn_id = ? and stage = 'SUMMARY'",
                        Boolean.class,
                        nextCurrent.turnId()))
                .isTrue();
        assertThat(chat.calledInsideTransaction).isFalse();
    }

    @Test
    void mem03到04_已解决歧义进入历史而未解决引导保留在原始窗口外() {
        var resolved = complete(null, "待选择方向", TurnStatus.WAITING_CLARIFICATION);
        addAssistantMessage(resolved, "请选择 A 或 B");
        var choice = complete(resolved.conversationId(), "选择 A", TurnStatus.ACTIONS_READY);
        jdbc.update("update qa_turn set source_turn_id = ? where id = ?", resolved.turnId(), choice.turnId());
        var unresolved = complete(
                resolved.conversationId(), "另一个待选择方向", TurnStatus.WAITING_CLARIFICATION);
        addAssistantMessage(unresolved, "请选择 C 或 D");
        var current = conversations.submitQuestion(
                resolved.conversationId(), UUID.randomUUID(), "当前问题");

        var snapshot = memory.prepare(current.turnId());

        assertThat(flatten(snapshot.recentTurns()))
                .containsExactly("待选择方向", "请选择 A 或 B", "选择 A")
                .doesNotContain("另一个待选择方向", "请选择 C 或 D");
    }

    @Test
    void mem08到11_摘要失败保留旧摘要且历史读取失败仍返回当前问题() {
        var first = complete(null, "已覆盖轮次", TurnStatus.ACTIONS_READY);
        conversations.installSummary(first.conversationId(), 0, first.messageId(), 1, 1, "old-summary");
        for (int index = 2; index <= 13; index++) {
            complete(first.conversationId(), "待摘要-" + index, TurnStatus.ACTIONS_READY);
        }
        var current = conversations.submitQuestion(first.conversationId(), UUID.randomUUID(), "当前问题");
        chat.fail = true;

        var failedSummary = memory.prepare(current.turnId());

        assertThat(failedSummary.summary()).isEqualTo("old-summary");
        assertThat(failedSummary.recentTurns()).hasSize(8);
        assertThat(failedSummary.degradations()).containsExactly("SUMMARY_UNAVAILABLE");
        assertThat(conversations.findSummaryHistory(first.conversationId())).hasSize(1);
        assertThat(jdbc.queryForMap(
                        "select outcome, reason_code, logical_call_id from qa_turn_stage "
                                + "where turn_id = ? and stage = 'SUMMARY'",
                        current.turnId()))
                .containsEntry("outcome", "DEGRADED")
                .containsEntry("reason_code", "SUMMARY_UNAVAILABLE");

        var unavailableHistory = new ConversationMemoryService(
                jdbc,
                conversations,
                new QaUnderstandingProperties(),
                PromptTemplateCatalog.fromClasspath(65_536),
                new FakeChat(),
                new JdbcTurnStageSink(jdbc),
                (conversationId, afterSequence, beforeSequence) -> {
                    throw new DataAccessResourceFailureException("simulated history failure");
                });
        var degradedHistory = unavailableHistory.prepare(current.turnId());

        assertThat(degradedHistory.summary()).isEqualTo("old-summary");
        assertThat(degradedHistory.recentTurns()).isEmpty();
        assertThat(degradedHistory.currentMessage().content()).isEqualTo("当前问题");
        assertThat(degradedHistory.degradations()).contains("HISTORY_UNAVAILABLE");
    }

    @Test
    void mem09_两个摘要生成竞争只安装一个新revision() throws Exception {
        UUID conversationId = null;
        for (int index = 1; index <= 12; index++) {
            conversationId = complete(conversationId, "并发轮次-" + index, TurnStatus.ACTIONS_READY)
                    .conversationId();
        }
        var current = conversations.submitQuestion(conversationId, UUID.randomUUID(), "并发当前问题");
        var barrier = new CyclicBarrier(2);
        var racingChat = new FakeChat(barrier);
        var racingMemory = new ConversationMemoryService(
                jdbc,
                conversations,
                new QaUnderstandingProperties(),
                PromptTemplateCatalog.fromClasspath(65_536),
                racingChat,
                new JdbcTurnStageSink(jdbc));

        var callers = Executors.newFixedThreadPool(2);
        try {
            var left = callers.submit(() -> racingMemory.prepare(current.turnId()));
            var right = callers.submit(() -> racingMemory.prepare(current.turnId()));
            var leftResult = left.get();
            var rightResult = right.get();

            assertThat(racingChat.commands).hasSize(2);
            assertThat(conversations.findSummaryHistory(conversationId)).hasSize(1);
            String installed = conversations.findCurrentSummary(conversationId).orElseThrow().content();
            assertThat(leftResult.summary()).isEqualTo(installed);
            assertThat(rightResult.summary()).isEqualTo(installed);
            assertThat(List.of(leftResult, rightResult).stream()
                            .flatMap(result -> result.degradations().stream()))
                    .contains("SUMMARY_CONFLICT");
        } finally {
            callers.shutdownNow();
        }
    }

    @Test
    void hist01_部分回答以固定未完成标记进入下一轮且标记不写入正文() {
        var previous = conversations.submitQuestion(null, UUID.randomUUID(), "上一问");
        jdbc.update("""
                update qa_turn
                   set status = 'STOPPED', terminal_reason = 'STOP_REQUESTED', completed_at = now()
                 where id = ?
                """, previous.turnId());
        jdbc.update("""
                insert into qa_message
                    (id, conversation_id, turn_id, sequence_no, role, message_type, content, incomplete)
                values (?, ?, ?, 2, 'ASSISTANT', 'ASSISTANT_ANSWER', '未完成正文', true)
                """, UUID.randomUUID(), previous.conversationId(), previous.turnId());
        var current = conversations.submitQuestion(previous.conversationId(), UUID.randomUUID(), "当前问题");

        var snapshot = memory.prepare(current.turnId());

        assertThat(snapshot.recentTurns()).singleElement().satisfies(turn -> {
            assertThat(turn.status()).isEqualTo(TurnStatus.STOPPED);
            assertThat(turn.messages()).extracting(ConversationMemoryService.MemoryMessage::content)
                    .containsExactly("上一问", "「以下回答未完成」\n未完成正文");
        });
        assertThat(jdbc.queryForObject(
                        "select content from qa_message where turn_id = ? and message_type = 'ASSISTANT_ANSWER'",
                        String.class,
                        previous.turnId()))
                .isEqualTo("未完成正文");
    }

    private ConversationPersistenceService.TurnSubmission complete(
            UUID conversationId, String message, TurnStatus status) {
        var submission = conversations.submitQuestion(conversationId, UUID.randomUUID(), message);
        conversations.markProcessing(submission.turnId());
        conversations.completeTurn(submission.turnId(), status, "DONE");
        return submission;
    }

    private void addAssistantMessage(
            ConversationPersistenceService.TurnSubmission turn, String content) {
        Long sequence = jdbc.queryForObject(
                "select coalesce(max(sequence_no), 0) + 1 from qa_message where conversation_id = ?",
                Long.class,
                turn.conversationId());
        jdbc.update("""
                insert into qa_message
                    (id, conversation_id, turn_id, sequence_no, role, message_type, content)
                values (?, ?, ?, ?, 'ASSISTANT', 'CLARIFICATION_GUIDANCE', ?)
                """, UUID.randomUUID(), turn.conversationId(), turn.turnId(), sequence, content);
    }

    private static List<String> flatten(List<ConversationMemoryService.HistoryTurn> turns) {
        return turns.stream()
                .flatMap(turn -> turn.messages().stream())
                .map(ConversationMemoryService.MemoryMessage::content)
                .toList();
    }

    private String schemaUrl() {
        return baseUrl + "?currentSchema=" + schema + ",public";
    }

    private static String withoutQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static final class FakeChat implements ChatClient {

        private final List<ChatCommand> commands = new CopyOnWriteArrayList<>();
        private final AtomicInteger invocations = new AtomicInteger();
        private final CyclicBarrier barrier;
        private volatile boolean fail;
        private volatile boolean calledInsideTransaction;

        private FakeChat() {
            this(null);
        }

        private FakeChat(CyclicBarrier barrier) {
            this.barrier = barrier;
        }

        @Override
        public ChatResult complete(ChatCommand command) {
            commands.add(command);
            calledInsideTransaction = org.springframework.transaction.support.TransactionSynchronizationManager
                    .isActualTransactionActive();
            if (barrier != null) {
                try {
                    barrier.await();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
            int invocation = invocations.incrementAndGet();
            if (fail) {
                throw new IllegalStateException("simulated summary failure");
            }
            UUID logicalCallId = UUID.randomUUID();
            return new ChatResult(
                    new AssistantMessage("summary-" + invocation, null, List.of()),
                    ChatFinishReason.STOP,
                    new TokenUsage(1, 1, 2),
                    new ExecutionInfo(
                            logicalCallId,
                            new RequestId("memory-test-" + invocation),
                            new ExecutionTarget(
                                    new ProviderId("fake-provider"),
                                    new DeploymentId("fake-chat"),
                                    new ModelIdentity("fake-chat-v1")),
                            1,
                            0,
                            0));
        }
    }
}
