package com.app.qa.intent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.app.qa.conversation.TurnStageSink;
import com.app.qa.model.IntentNodeType;
import com.app.qa.model.StageOutcome;
import com.app.qa.model.SystemAction;
import com.app.qa.prompt.IntentCatalogEncoder;
import com.app.qa.prompt.PromptBudgetExceededException;
import com.app.qa.prompt.PromptTemplateCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class IntentScoringServiceTests {

    private static final UUID GROUP_ID = id(10);
    private static final UUID KB_A = id(101);
    private static final UUID KB_B = id(102);
    private static final UUID KB_C = id(103);
    private static final UUID KB_D = id(104);
    private static final UUID DISABLED = id(105);

    private ExecutorService executor;
    private FakeChat chat;
    private IntentScoringService scoring;
    private List<TurnStageSink.StageEvent> stages;

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(3);
        stages = new CopyOnWriteArrayList<>();
        chat = new FakeChat(command -> "[]");
        scoring = service(chat, PromptTemplateCatalog.fromClasspath(65_536));
    }

    @AfterEach
    void stopExecutor() {
        executor.shutdownNow();
        Thread.interrupted();
    }

    @Test
    void int01到05_三个子问题共享快照并在专用执行器并行各调用一次() {
        var barrier = new CyclicBarrier(3);
        chat = new FakeChat(command -> {
            try {
                barrier.await();
            } catch (Exception exception) {
                throw new IllegalStateException(exception);
            }
            return "[]";
        });
        scoring = service(chat, PromptTemplateCatalog.fromClasspath(65_536));
        var snapshot = snapshot();

        var result = scoring.score(id(900), snapshot, List.of(
                question(0, "第一问"), question(1, "第二问"), question(2, "第三问")));

        assertThat(result.revisionId()).isSameAs(snapshot.revisionId());
        assertThat(chat.commands).hasSize(3);
        assertThat(stages).hasSize(3).allSatisfy(stage -> {
            assertThat(stage.stage().name()).isEqualTo("INTENT_SCORE");
            assertThat(stage.outcome()).isEqualTo(StageOutcome.SUCCEEDED);
            assertThat(stage.logicalCallId()).isNotNull();
            assertThat(stage.intentRevisionId()).isSameAs(snapshot.revisionId());
        });
        assertThat(chat.commands).allSatisfy(command -> {
            assertThat(command.tier()).isEqualTo(ServiceTier.BALANCED);
            String prompt = ((UserMessage) command.messages().get(1)).text();
            assertThat(prompt)
                    .contains(KB_A.toString(), KB_B.toString(), KB_C.toString(), KB_D.toString())
                    .doesNotContain(DISABLED.toString(), "prompt-only-secret");
        });
    }

    @Test
    void int06到10_解析围栏并丢弃非法候选重复节点取最高且边界035保留() {
        chat = new FakeChat(command -> """
                ```json
                [
                  {"id":"%s","score":0.35},
                  {"id":"%s","score":0.90},
                  {"id":"%s","score":0.70},
                  {"id":"%s","score":0.349},
                  {"id":"%s","score":1e400},
                  {"id":"%s","score":0.35},
                  {"id":"%s","score":"0.8"},
                  {"id":"00000000-0000-0000-0000-000000009999","score":0.99}
                ]
                ```
                """.formatted(KB_A, KB_A, KB_B, KB_C, KB_C, KB_D, DISABLED));
        scoring = service(chat, PromptTemplateCatalog.fromClasspath(65_536));

        var result = scoring.score(id(900), snapshot(), List.of(question(0, "问题")));

        assertThat(result.questions().get(0).candidates())
                .extracting(IntentScoringService.IntentCandidate::nodeId)
                .containsExactly(KB_A, KB_B, KB_D);
        assertThat(result.questions().get(0).candidates())
                .extracting(IntentScoringService.IntentCandidate::score)
                .containsExactly(0.90, 0.70, 0.35);
    }

    @Test
    void int11到14_全局三席先保证每个有命中子问题一席再按分数补位() {
        chat = new FakeChat(command -> {
            String prompt = ((UserMessage) command.messages().get(1)).text();
            if (prompt.contains("问题甲")) {
                return scores(KB_A, 0.99, KB_B, 0.98, KB_C, 0.97);
            }
            if (prompt.contains("问题乙")) {
                return scores(KB_D, 0.60);
            }
            throw new IllegalStateException("single question failure");
        });
        scoring = service(chat, PromptTemplateCatalog.fromClasspath(65_536));

        var result = scoring.score(id(900), snapshot(), List.of(
                question(0, "问题甲"), question(1, "问题乙"), question(2, "问题丙")));

        assertThat(result.questions().get(0).candidates())
                .extracting(IntentScoringService.IntentCandidate::nodeId)
                .containsExactly(KB_A, KB_B);
        assertThat(result.questions().get(1).candidates())
                .extracting(IntentScoringService.IntentCandidate::nodeId)
                .containsExactly(KB_D);
        assertThat(result.questions().get(2).candidates()).isEmpty();
        assertThat(result.questions().get(2).reasonCode()).isEqualTo("INTENT_SCORING_UNAVAILABLE");
    }

    @Test
    void int09到13_同分候选按节点ID稳定排序且单问题最多保留三个() {
        chat = new FakeChat(command -> scores(KB_D, 0.8, KB_C, 0.8, KB_B, 0.8, KB_A, 0.8));
        scoring = service(chat, PromptTemplateCatalog.fromClasspath(65_536));

        var result = scoring.score(id(900), snapshot(), List.of(question(0, "同分问题")));

        assertThat(result.questions().get(0).candidates())
                .extracting(IntentScoringService.IntentCandidate::nodeId)
                .containsExactly(KB_A, KB_B, KB_C);
        assertThat(result.questions().get(0).candidates())
                .extracting(IntentScoringService.IntentCandidate::rank)
                .containsExactly(1, 2, 3);
    }

    @Test
    void int15_全部返回非法候选时形成可察觉NoMatch() {
        chat = new FakeChat(command -> "[{\"id\":\"not-a-uuid\",\"score\":0.9}]");
        scoring = service(chat, PromptTemplateCatalog.fromClasspath(65_536));

        var result = scoring.score(id(900), snapshot(), List.of(question(0, "无匹配")));

        assertThat(result.questions().get(0).candidates()).isEmpty();
        assertThat(result.questions().get(0).reasonCode()).isEqualTo("NO_VALID_INTENT_SCORE");
    }

    @Test
    void int16_Prompt超预算时整批零模型调用且不截断目录() {
        scoring = service(chat, PromptTemplateCatalog.fromClasspath(32));

        assertThatThrownBy(() -> scoring.score(id(900), snapshot(), List.of(question(0, "问题"))))
                .isInstanceOf(PromptBudgetExceededException.class);
        assertThat(chat.commands).isEmpty();
    }

    @Test
    void int05_等待并行结果被中断时恢复线程标志并显式失败() {
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> scoring.score(id(900), snapshot(), List.of(question(0, "问题"))))
                .isInstanceOf(IntentScoringService.IntentScoringInterruptedException.class);
        assertThat(Thread.currentThread().isInterrupted()).isTrue();
    }

    private IntentScoringService service(ChatClient client, PromptTemplateCatalog prompts) {
        return new IntentScoringService(
                client,
                prompts,
                new IntentCatalogEncoder(new ObjectMapper()),
                new ObjectMapper(),
                new QaUnderstandingProperties(),
                executor,
                stages::add);
    }

    private static IntentScoringService.ScoringQuestion question(int ordinal, String text) {
        return new IntentScoringService.ScoringQuestion(UUID.randomUUID(), ordinal, text);
    }

    private static IntentTreeStore.IntentTreeSnapshot snapshot() {
        return new IntentTreeStore.IntentTreeSnapshot(
                id(1),
                1,
                OffsetDateTime.parse("2026-08-07T12:00:00+08:00"),
                List.of(
                        node(GROUP_ID, IntentTreeStore.VIRTUAL_ROOT_ID, 1, IntentNodeType.GROUP, true, null, null),
                        node(KB_A, GROUP_ID, 2, IntentNodeType.KB, true, 11L, null),
                        node(KB_B, GROUP_ID, 2, IntentNodeType.KB, true, 12L, null),
                        node(KB_C, GROUP_ID, 2, IntentNodeType.SYSTEM, true, null, SystemAction.ASSISTANT_IDENTITY),
                        node(KB_D, GROUP_ID, 2, IntentNodeType.KB, true, 14L, null),
                        node(DISABLED, GROUP_ID, 2, IntentNodeType.KB, false, 15L, null)));
    }

    private static IntentTreeStore.IntentNode node(
            UUID nodeId,
            UUID parentId,
            int depth,
            IntentNodeType type,
            boolean enabled,
            Long knowledgeBaseId,
            SystemAction action) {
        return new IntentTreeStore.IntentNode(
                nodeId,
                parentId,
                depth,
                0,
                type,
                "节点-" + nodeId,
                type == IntentNodeType.GROUP ? "分组" : "可执行描述",
                type == IntentNodeType.GROUP ? List.of() : List.of("示例"),
                knowledgeBaseId,
                action,
                type == IntentNodeType.GROUP ? null : "prompt-only-secret",
                enabled,
                false);
    }

    private static String scores(Object... values) {
        var json = new StringBuilder("[");
        for (int index = 0; index < values.length; index += 2) {
            if (index > 0) {
                json.append(',');
            }
            json.append("{\"id\":\"")
                    .append(values[index])
                    .append("\",\"score\":")
                    .append(values[index + 1])
                    .append('}');
        }
        return json.append(']').toString();
    }

    private static UUID id(long suffix) {
        return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
    }

    private static final class FakeChat implements ChatClient {

        private final List<ChatCommand> commands = new CopyOnWriteArrayList<>();
        private final Function<ChatCommand, String> response;

        private FakeChat(Function<ChatCommand, String> response) {
            this.response = response;
        }

        @Override
        public ChatResult complete(ChatCommand command) {
            commands.add(command);
            return new ChatResult(
                    new AssistantMessage(response.apply(command), null, List.of()),
                    ChatFinishReason.STOP,
                    new TokenUsage(1, 1, 2),
                    new ExecutionInfo(
                            UUID.randomUUID(),
                            new RequestId("intent-test-" + commands.size()),
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
