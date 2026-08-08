package com.app.qa.intent;

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
import com.app.qa.config.QaUnderstandingProperties;
import com.app.qa.conversation.TurnStageSink;
import com.app.qa.model.IntentNodeType;
import com.app.qa.model.RetrievalActionType;
import com.app.qa.model.StageOutcome;
import com.app.qa.model.SystemAction;
import com.app.qa.model.TurnStatus;
import com.app.qa.prompt.PromptTemplateCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AmbiguityActionServiceTests {

    private static final UUID KB_A = id(101);
    private static final UUID KB_B = id(102);
    private static final UUID KB_C = id(103);
    private static final UUID CATEGORY_A = id(201);
    private static final UUID CATEGORY_B = id(202);
    private static final UUID CATEGORY_C = id(203);
    private static final UUID SYSTEM_A = id(301);
    private static final UUID SYSTEM_B = id(302);

    private FakeChat chat;
    private List<TurnStageSink.StageEvent> stages;
    private AmbiguityActionService service;

    @BeforeEach
    void setUp() {
        chat = new FakeChat(command -> "{\"ambiguous\":false}");
        stages = new CopyOnWriteArrayList<>();
        service = service(chat);
    }

    @Test
    void amb01到03_多问题同品类System和低质量候选均不伪造歧义() {
        var multi = service.decide(id(900), batch(
                scored(0, null, kb(KB_A, CATEGORY_A, 0.91)),
                scored(1, null, system(SYSTEM_A, SystemAction.GREETING, 0.95))));
        var sameCategory = service.decide(id(901), batch(scored(
                0, null, kb(KB_A, CATEGORY_A, 0.80), kb(KB_B, CATEGORY_A, 0.76))));
        var lowQuality = service.decide(id(902), batch(scored(
                0, null, kb(KB_A, CATEGORY_A, 0.59), kb(KB_B, CATEGORY_B, 0.58))));

        assertThat(multi.status()).isEqualTo(TurnStatus.ACTIONS_READY);
        assertThat(multi.actions())
                .extracting(AmbiguityActionService.RetrievalAction::type)
                .contains(RetrievalActionType.KB_TARGETED, RetrievalActionType.GLOBAL_FALLBACK);
        assertThat(sameCategory.status()).isEqualTo(TurnStatus.ACTIONS_READY);
        assertThat(lowQuality.status()).isEqualTo(TurnStatus.ACTIONS_READY);
        assertThat(lowQuality.options()).isEmpty();
        assertThat(chat.commands).isEmpty();
    }

    @Test
    void amb04到06_跨品类边界015直接引导灰区仅复核一次且大于030不调用() {
        var direct = service.decide(id(900), batch(scored(
                0, null, kb(KB_A, CATEGORY_A, 0.80), kb(KB_B, CATEGORY_B, 0.65))));
        var gray = service.decide(id(901), batch(scored(
                0, null, kb(KB_A, CATEGORY_A, 0.800_000_1), kb(KB_B, CATEGORY_B, 0.65))));
        var clear = service.decide(id(902), batch(scored(
                0, null, kb(KB_A, CATEGORY_A, 0.91), kb(KB_B, CATEGORY_B, 0.60))));

        assertThat(direct.status()).isEqualTo(TurnStatus.WAITING_CLARIFICATION);
        assertThat(gray.status()).isEqualTo(TurnStatus.ACTIONS_READY);
        assertThat(clear.status()).isEqualTo(TurnStatus.ACTIONS_READY);
        assertThat(chat.commands).hasSize(1).allSatisfy(command ->
                assertThat(command.tier()).isEqualTo(ServiceTier.BALANCED));
    }

    @Test
    void amb07到08_灰区非法输出保守引导且固定文案最多三个可读选项() {
        chat = new FakeChat(command -> "{\"ambiguous\":\"maybe\"}");
        service = service(chat);

        var result = service.decide(id(900), batch(scored(
                0,
                null,
                kb(KB_A, CATEGORY_A, 0.90),
                kb(KB_B, CATEGORY_B, 0.70),
                kb(KB_C, CATEGORY_C, 0.69))));

        assertThat(result.status()).isEqualTo(TurnStatus.WAITING_CLARIFICATION);
        assertThat(result.guidance()).startsWith("请确认你想了解的方向：");
        assertThat(result.options()).hasSize(3);
        assertThat(result.options())
                .extracting(AmbiguityActionService.ClarificationOption::label)
                .containsExactly("服务 > 方向-101", "服务 > 方向-102", "服务 > 方向-103");
        assertThat(result.actions()).isEmpty();
        assertThat(stages).anySatisfy(stage -> {
            assertThat(stage.outcome()).isEqualTo(StageOutcome.DEGRADED);
            assertThat(stage.reasonCode()).isEqualTo("AMBIGUITY_CONFIRM_INVALID_RESPONSE");
        });
    }

    @Test
    void amb10_结构化选择线索跳过同一歧义并直接映射所选节点() {
        var result = service.decide(
                id(900),
                batch(scored(0, null, kb(KB_A, CATEGORY_A, 0.80), kb(KB_B, CATEGORY_B, 0.70))),
                KB_B);

        assertThat(result.status()).isEqualTo(TurnStatus.ACTIONS_READY);
        assertThat(result.options()).isEmpty();
        assertThat(result.actions())
                .filteredOn(action -> action.type() == RetrievalActionType.KB_TARGETED)
                .singleElement()
                .extracting(AmbiguityActionService.RetrievalAction::intentNodeId)
                .isEqualTo(KB_B);
        assertThat(chat.commands).isEmpty();
    }

    @Test
    void act01到05_阈值边界和补充规则产生稳定声明式动作() {
        var threshold = service.decide(id(900), batch(scored(
                0, null, kb(KB_A, CATEGORY_A, 0.40), kb(KB_B, CATEGORY_A, 0.399_999))));
        var fallbackBoundary = service.decide(id(901), batch(scored(
                0, null, kb(KB_A, CATEGORY_A, 0.60), kb(KB_B, CATEGORY_A, 0.55))));
        var supplementBoundary = service.decide(id(902), batch(scored(
                0, null, kb(KB_A, CATEGORY_A, 0.80))));
        var supplementBelow = service.decide(id(903), batch(scored(
                0, null, kb(KB_A, CATEGORY_A, 0.799_999))));

        assertThat(threshold.actions())
                .filteredOn(action -> action.type() == RetrievalActionType.KB_TARGETED)
                .extracting(AmbiguityActionService.RetrievalAction::intentNodeId)
                .containsExactly(KB_A);
        assertThat(threshold.actions())
                .extracting(AmbiguityActionService.RetrievalAction::type)
                .contains(RetrievalActionType.GLOBAL_FALLBACK);
        assertThat(fallbackBoundary.actions())
                .extracting(AmbiguityActionService.RetrievalAction::type)
                .doesNotContain(RetrievalActionType.GLOBAL_FALLBACK);
        assertThat(supplementBoundary.actions())
                .extracting(AmbiguityActionService.RetrievalAction::type)
                .doesNotContain(RetrievalActionType.GLOBAL_FALLBACK);
        assertThat(supplementBelow.actions())
                .extracting(AmbiguityActionService.RetrievalAction::type)
                .contains(RetrievalActionType.GLOBAL_FALLBACK);
    }

    @Test
    void act02_无候选按评分结果区分NoMatch与GlobalFallback() {
        var failed = service.decide(id(900), batch(scored(0, "INTENT_SCORING_UNAVAILABLE")));
        var empty = service.decide(id(901), batch(scored(0, null)));

        assertThat(failed.actions()).singleElement().satisfies(action -> {
            assertThat(action.type()).isEqualTo(RetrievalActionType.NO_MATCH);
            assertThat(action.reasonCode()).isEqualTo("INTENT_SCORING_UNAVAILABLE");
        });
        assertThat(empty.actions()).singleElement().satisfies(action -> {
            assertThat(action.type()).isEqualTo(RetrievalActionType.GLOBAL_FALLBACK);
            assertThat(action.reasonCode()).isEqualTo("NO_CANDIDATE");
        });
    }

    @Test
    void act06到08_同一System才成系统动作且复合问题不吞KB也不触发下游能力() {
        var sameSystem = service.decide(id(900), batch(
                scored(0, null, system(SYSTEM_A, SystemAction.GREETING, 0.9)),
                scored(1, null, system(SYSTEM_A, SystemAction.GREETING, 0.8))));
        var mixed = service.decide(id(901), batch(
                scored(0, null, system(SYSTEM_A, SystemAction.GREETING, 0.9)),
                scored(1, null, kb(KB_A, CATEGORY_A, 0.9))));

        assertThat(sameSystem.actions())
                .extracting(AmbiguityActionService.RetrievalAction::type)
                .containsOnly(RetrievalActionType.SYSTEM);
        assertThat(mixed.actions())
                .extracting(AmbiguityActionService.RetrievalAction::type)
                .contains(RetrievalActionType.GLOBAL_FALLBACK, RetrievalActionType.KB_TARGETED)
                .doesNotContain(RetrievalActionType.SYSTEM);
        assertThat(chat.commands).isEmpty();
    }

    private AmbiguityActionService service(ChatClient client) {
        return new AmbiguityActionService(
                client,
                PromptTemplateCatalog.fromClasspath(65_536),
                new ObjectMapper(),
                new QaUnderstandingProperties(),
                stages::add);
    }

    private static IntentScoringService.ScoringBatch batch(
            IntentScoringService.ScoredQuestion... questions) {
        return new IntentScoringService.ScoringBatch(id(1), List.of(questions));
    }

    private static IntentScoringService.ScoredQuestion scored(
            int ordinal, String reasonCode, IntentScoringService.IntentCandidate... candidates) {
        return new IntentScoringService.ScoredQuestion(
                id(500 + ordinal), ordinal, "问题-" + ordinal, List.of(candidates), reasonCode, null);
    }

    private static IntentScoringService.IntentCandidate kb(
            UUID nodeId, UUID categoryId, double score) {
        return new IntentScoringService.IntentCandidate(
                nodeId,
                IntentNodeType.KB,
                "服务 > 方向-" + Long.parseLong(nodeId.toString().substring(24)),
                Long.parseLong(nodeId.toString().substring(24)),
                null,
                categoryId,
                score,
                1);
    }

    private static IntentScoringService.IntentCandidate system(
            UUID nodeId, SystemAction action, double score) {
        return new IntentScoringService.IntentCandidate(
                nodeId,
                IntentNodeType.SYSTEM,
                "系统 > " + action,
                null,
                action,
                nodeId,
                score,
                1);
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
                            new RequestId("ambiguity-test-" + commands.size()),
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
