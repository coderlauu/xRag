package com.app.qa.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.ChatStreamEvent;
import com.app.ai.model.StreamingChatClient;
import com.app.ai.model.TokenUsage;
import com.app.qa.prompt.GenerationPromptAssembler;
import com.app.qa.prompt.PromptWindowBudget;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/** 工单 06：只跨 GenerationTasks 外部 seam，模型是唯一假 adapter。 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class GenerationTasksIntegrationTests {

    private final String baseUrl = withoutQuery(
            System.getProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/app"));
    private final String username = System.getProperty("spring.datasource.username", "app");
    private final String password = System.getProperty("spring.datasource.password", "app");

    private String schema;
    private JdbcTemplate jdbc;
    private DataSourceTransactionManager transactionManager;
    private ExecutorService generationExecutor;
    private ScheduledExecutorService deadlines;

    @BeforeAll
    void createSchema() throws SQLException {
        schema = "xrag_qa_generation_" + UUID.randomUUID().toString().replace("-", "");
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
    void reset() {
        jdbc.execute("truncate qa_conversation cascade");
        generationExecutor = Executors.newFixedThreadPool(2);
        deadlines = Executors.newSingleThreadScheduledExecutor();
    }

    @AfterEach
    void shutdownExecutors() {
        generationExecutor.shutdownNow();
        deadlines.shutdownNow();
    }

    @AfterAll
    void dropSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void 正常假流只通过问答事件完成并保存答案() {
        var model = new FakeStreamingChatClient();
        model.stream.set(Flux.just(
                new ChatStreamEvent.TextDelta("你好，"),
                new ChatStreamEvent.TextDelta("这是答案"),
                new ChatStreamEvent.Completed(ChatFinishReason.STOP, new TokenUsage(3, 4, 7))));
        var events = new CopyOnWriteArrayList<GenerationEvent>();
        var seed = seedReadyTurn();

        var submission = tasks(model).submit(command(seed), events::add);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(events).anyMatch(GenerationEvent.Completed.class::isInstance));
        assertThat(submission.queued()).isFalse();
        assertThat(events).hasSize(5);
        assertThat(events.get(0)).isInstanceOf(GenerationEvent.Accepted.class);
        assertThat(events.get(1)).isEqualTo(new GenerationEvent.GenerationStarted(2, false));
        assertThat(events.subList(2, 4)).allMatch(GenerationEvent.AnswerDelta.class::isInstance);
        assertThat(events.get(4)).isInstanceOf(GenerationEvent.Completed.class);
        assertThat(events).extracting(GenerationEvent::sequence).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(jdbc.queryForMap("select status, answer_saved from qa_generation_task where id = ?", submission.taskId().value()))
                .containsEntry("status", "COMPLETED")
                .containsEntry("answer_saved", true);
        assertThat(model.invocations).hasValue(1);
    }

    @Test
    void 模型异常只发归一化Failed且保存已观察部分(CapturedOutput output) {
        var model = new FakeStreamingChatClient();
        model.stream.set(Flux.concat(
                Flux.just(new ChatStreamEvent.TextDelta("部分")),
                Flux.error(new IllegalStateException("https://secret-host/key=private"))));
        var events = new CopyOnWriteArrayList<GenerationEvent>();
        var seed = seedReadyTurn();

        tasks(model).submit(command(seed), events::add);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(events).anyMatch(GenerationEvent.Failed.class::isInstance));
        var failed = (GenerationEvent.Failed) events.get(events.size() - 1);
        assertThat(failed.code()).isEqualTo(GenerationFailureCode.MODEL_UNAVAILABLE);
        assertThat(failed.saved()).isTrue();
        assertThat(events.toString()).doesNotContain("secret-host", "private");
        assertThat(output.getAll()).doesNotContain("secret-host", "private");
        assertThat(jdbc.queryForObject(
                        "select row_to_json(t)::text from qa_generation_task t", String.class))
                .doesNotContain("secret-host", "private");
        assertThat(jdbc.queryForObject(
                        "select incomplete from qa_message where id = ?", Boolean.class, failed.assistantMessageId()))
                .isTrue();
    }

    @Test
    void 四类Prompt输入只到模型且Sse元数据诊断表和日志无副本(CapturedOutput output) {
        String question = "PRIV_Q_41b7";
        String history = "PRIV_H_8a2c";
        String evidence = "PRIV_E_95d1";
        String tool = "PRIV_T_2fc4";
        String answer = "PRIV_A_77e0";
        var model = new FakeStreamingChatClient();
        model.stream.set(Flux.just(
                new ChatStreamEvent.TextDelta(answer),
                new ChatStreamEvent.Completed(ChatFinishReason.STOP, new TokenUsage(1, 1, 2))));
        var events = new CopyOnWriteArrayList<GenerationEvent>();
        var seed = seedReadyTurn();
        var material = new GenerationPromptAssembler.Material(
                "可信规则",
                new GenerationPromptAssembler.Item("question", question),
                List.of(new GenerationPromptAssembler.Item("history", history)),
                List.of(),
                List.of(new GenerationPromptAssembler.Item("evidence", evidence)),
                List.of(new GenerationPromptAssembler.Item("tool", tool)),
                List.of());

        tasks(model).submit(new GenerationCommand(
                seed.conversationId, seed.turnId, seed.userMessageId, material), events::add);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(events).anyMatch(GenerationEvent.Completed.class::isInstance));
        String providerCommand = model.command.get().toString();
        assertThat(providerCommand).contains(question, history, evidence, tool);
        String metadata = events.stream()
                .filter(event -> !(event instanceof GenerationEvent.AnswerDelta))
                .toList().toString();
        assertThat(metadata).doesNotContain(question, history, evidence, tool, answer);
        assertThat(jdbc.queryForObject(
                        "select row_to_json(t)::text from qa_generation_task t", String.class))
                .doesNotContain(question, history, evidence, tool, answer);
        assertThat(jdbc.queryForList(
                        "select row_to_json(s)::text from qa_turn_stage s where turn_id = ?",
                        String.class, seed.turnId))
                .allSatisfy(row -> assertThat(row).doesNotContain(question, history, evidence, tool, answer));
        assertThat(output.getAll()).doesNotContain(question, history, evidence, tool, answer);
        assertThat(events).anySatisfy(event -> assertThat(event)
                .isEqualTo(new GenerationEvent.AnswerDelta(3, answer)));
        assertThat(jdbc.queryForObject(
                        "select content from qa_message where turn_id = ? and message_type = 'ASSISTANT_ANSWER'",
                        String.class, seed.turnId))
                .isEqualTo(answer);
    }

    @Test
    void 生成中停止取消订阅并以Stopped保存部分() {
        var sink = Sinks.many().unicast().<ChatStreamEvent>onBackpressureBuffer();
        var cancelled = new AtomicBoolean();
        var model = new FakeStreamingChatClient();
        model.stream.set(sink.asFlux().doOnCancel(() -> cancelled.set(true)));
        var events = new CopyOnWriteArrayList<GenerationEvent>();
        var seed = seedReadyTurn();
        var tasks = tasks(model);
        var submission = tasks.submit(command(seed), events::add);
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(model.invocations).hasValue(1));
        sink.tryEmitNext(new ChatStreamEvent.TextDelta("已生成部分"));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(events).anyMatch(GenerationEvent.AnswerDelta.class::isInstance));

        var stopped = tasks.stop(new GenerationTasks.OwnedTask(
                submission.taskId(), seed.conversationId, seed.turnId));

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(cancelled).isTrue());
        assertThat(stopped).isEqualTo(GenerationTasks.StopResult.stopped(GenerationTaskStatus.STOPPED, false));
        var terminal = (GenerationEvent.Stopped) events.get(events.size() - 1);
        assertThat(terminal.hasPartialAnswer()).isTrue();
        assertThat(terminal.saved()).isTrue();
    }

    @Test
    void 排队中停止使队列项失效且不调用模型() {
        var model = new FakeStreamingChatClient();
        var first = seedReadyTurn();
        var queued = seedReadyTurn();
        var queuedEvents = new CopyOnWriteArrayList<GenerationEvent>();
        var service = tasks(model, 1);
        var firstSubmission = service.submit(command(first), event -> {});
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(model.invocations).hasValue(1));
        var queuedSubmission = service.submit(command(queued), queuedEvents::add);
        assertThat(queuedSubmission.queued()).isTrue();

        var stopped = service.stop(new GenerationTasks.OwnedTask(
                queuedSubmission.taskId(), queued.conversationId, queued.turnId));

        assertThat(stopped).isEqualTo(GenerationTasks.StopResult.stopped(
                GenerationTaskStatus.STOPPED, false));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(queuedEvents)
                .anyMatch(GenerationEvent.Stopped.class::isInstance));
        assertThat(model.invocations).hasValue(1);
        assertThat(jdbc.queryForMap(
                        "select status, answer_saved from qa_generation_task where id = ?",
                        queuedSubmission.taskId().value()))
                .containsEntry("status", "STOPPED")
                .containsEntry("answer_saved", false);
        service.stop(new GenerationTasks.OwnedTask(
                firstSubmission.taskId(), first.conversationId, first.turnId));
    }

    @Test
    void 工具未结算时拒绝且零模型调用零任务() {
        var model = new FakeStreamingChatClient();
        var seed = seedReadyTurn();
        jdbc.update("""
                insert into qa_tool_call
                    (id, turn_id, ordinal, tool_name, side_effect, status)
                values (?, ?, 0, 'submit-ticket', true, 'PENDING_CONFIRM')
                """, UUID.randomUUID(), seed.turnId);

        assertThatThrownBy(() -> tasks(model).submit(command(seed), event -> {}))
                .isInstanceOf(ToolsNotSettledException.class);
        assertThat(model.invocations).hasValue(0);
        assertThat(jdbc.queryForObject("select count(*) from qa_generation_task", Integer.class)).isZero();
    }

    @Test
    void 推送断连不取消后台任务且答案仍可恢复() {
        var model = new FakeStreamingChatClient();
        model.stream.set(Flux.just(
                new ChatStreamEvent.TextDelta("断连后答案"),
                new ChatStreamEvent.Completed(ChatFinishReason.STOP, new TokenUsage(1, 1, 2))));
        var seed = seedReadyTurn();

        var submission = tasks(model).submit(command(seed), event -> {
            throw new IllegalStateException("client disconnected");
        });

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(jdbc.queryForMap(
                                "select status, answer_saved from qa_generation_task where id = ?",
                                submission.taskId().value()))
                        .containsEntry("status", "COMPLETED")
                        .containsEntry("answer_saved", true));
        assertThat(jdbc.queryForObject(
                        "select content from qa_message where turn_id = ? and message_type = 'ASSISTANT_ANSWER'",
                        String.class, seed.turnId))
                .isEqualTo("断连后答案");
    }

    @Test
    void 停机统一清算生成中与排队任务并拒绝新提交() {
        var model = new FakeStreamingChatClient();
        var first = seedReadyTurn();
        var second = seedReadyTurn();
        var service = tasks(model, 1);
        var firstTask = service.submit(command(first), event -> {});
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(model.invocations).hasValue(1));
        var secondTask = service.submit(command(second), event -> {});
        assertThat(secondTask.queued()).isTrue();

        service.close();

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(jdbc.queryForList(
                        "select status from qa_generation_task where id in (?, ?) order by id",
                        String.class, firstTask.taskId().value(), secondTask.taskId().value()))
                .containsExactly("FAILED", "FAILED"));
        assertThat(jdbc.queryForList(
                        "select distinct terminal_reason from qa_generation_task where id in (?, ?)",
                        String.class, firstTask.taskId().value(), secondTask.taskId().value()))
                .containsExactly("SERVICE_SHUTDOWN");
        assertThatThrownBy(() -> service.submit(command(seedReadyTurn()), event -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shutting down");
    }

    @Test
    void 首包与总生成Deadline分别归一化并释放订阅() {
        var firstByteModel = new FakeStreamingChatClient();
        var firstEvents = new CopyOnWriteArrayList<GenerationEvent>();
        tasks(firstByteModel, 1, Duration.ofMillis(40), Duration.ofSeconds(1))
                .submit(command(seedReadyTurn()), firstEvents::add);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(firstEvents)
                .anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(
                        GenerationEvent.Failed.class,
                        failed -> assertThat(failed.code()).isEqualTo(GenerationFailureCode.FIRST_BYTE_TIMEOUT))));

        var deadlineModel = new FakeStreamingChatClient();
        deadlineModel.stream.set(Flux.concat(
                Flux.just(new ChatStreamEvent.TextDelta("总时限前的部分")), Flux.never()));
        var deadlineEvents = new CopyOnWriteArrayList<GenerationEvent>();
        tasks(deadlineModel, 1, Duration.ofMillis(40), Duration.ofMillis(100))
                .submit(command(seedReadyTurn()), deadlineEvents::add);
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(deadlineEvents)
                .anySatisfy(event -> assertThat(event).isInstanceOfSatisfying(
                        GenerationEvent.Failed.class, failed -> {
                            assertThat(failed.code()).isEqualTo(GenerationFailureCode.GENERATION_TIMEOUT);
                            assertThat(failed.saved()).isTrue();
                        })));
    }

    private DefaultGenerationTasks tasks(StreamingChatClient model) {
        return tasks(model, 2);
    }

    private DefaultGenerationTasks tasks(StreamingChatClient model, int maxConcurrency) {
        return tasks(model, maxConcurrency, Duration.ofSeconds(15), Duration.ofSeconds(120));
    }

    private DefaultGenerationTasks tasks(
            StreamingChatClient model,
            int maxConcurrency,
            Duration firstByteTimeout,
            Duration modelDeadline) {
        var store = new JdbcGenerationTaskStore(jdbc, transactionManager);
        var settlementRepository = new JdbcTaskSettlementRepository(
                jdbc, transactionManager, new JdbcAssistantAnswerStore(jdbc));
        var queue = new FairGenerationAdmissionQueue(
                maxConcurrency,
                32,
                Duration.ofSeconds(30),
                Clock.systemUTC(),
                FairGenerationAdmissionQueue.DeadlineScheduler.using(deadlines, Clock.systemUTC()));
        return new DefaultGenerationTasks(
                model,
                new GenerationPromptAssembler(PromptWindowBudget.fixedInputBytes(20_000)),
                store,
                settlementRepository,
                queue,
                generationExecutor,
                deadlines,
                firstByteTimeout,
                modelDeadline);
    }

    private Seed seedReadyTurn() {
        var conversationId = UUID.randomUUID();
        var turnId = UUID.randomUUID();
        var userMessageId = UUID.randomUUID();
        jdbc.update("insert into qa_conversation (id, title, last_active_at) values (?, '测试对话', now())", conversationId);
        jdbc.update("""
                insert into qa_turn
                    (id, conversation_id, client_request_id, user_message_id, status, completed_at)
                values (?, ?, ?, ?, 'ACTIONS_READY', now())
                """, turnId, conversationId, UUID.randomUUID(), userMessageId);
        jdbc.update("""
                insert into qa_message
                    (id, conversation_id, turn_id, sequence_no, role, message_type, content)
                values (?, ?, ?, 1, 'USER', 'USER_QUERY', '问题')
                """, userMessageId, conversationId, turnId);
        return new Seed(conversationId, turnId, userMessageId);
    }

    private GenerationCommand command(Seed seed) {
        return new GenerationCommand(
                seed.conversationId,
                seed.turnId,
                seed.userMessageId,
                new GenerationPromptAssembler.Material(
                        "可信规则",
                        new GenerationPromptAssembler.Item("question", "问题"),
                        List.of(), List.of(), List.of(), List.of(), List.of()));
    }

    private String schemaUrl() {
        return baseUrl + "?currentSchema=" + schema + ",public";
    }

    private static String withoutQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private record Seed(UUID conversationId, UUID turnId, UUID userMessageId) {}

    private static final class FakeStreamingChatClient implements StreamingChatClient {
        private final AtomicInteger invocations = new AtomicInteger();
        private final AtomicReference<Flux<ChatStreamEvent>> stream = new AtomicReference<>(Flux.never());
        private final AtomicReference<ChatCommand> command = new AtomicReference<>();

        @Override
        public Flux<ChatStreamEvent> stream(ChatCommand command) {
            invocations.incrementAndGet();
            this.command.set(command);
            return stream.get();
        }
    }
}
