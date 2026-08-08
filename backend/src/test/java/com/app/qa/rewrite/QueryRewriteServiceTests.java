package com.app.qa.rewrite;

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
import com.app.ai.model.FailurePhase;
import com.app.ai.model.ModelCallException;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ProviderId;
import com.app.ai.model.RequestId;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.TerminalCause;
import com.app.ai.model.TerminationReason;
import com.app.ai.model.TokenUsage;
import com.app.ai.model.UserMessage;
import com.app.qa.model.RewriteMode;
import com.app.qa.prompt.PromptTemplateCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class QueryRewriteServiceTests {

    @Test
    void rw01_NFKC合并Unicode空白且空结果拒绝() {
        assertThat(QuestionNormalizer.normalize("  ＡＢＣ\u3000  退款\n多久  ")).isEqualTo("ABC 退款 多久");
        assertThatThrownBy(() -> QuestionNormalizer.normalize(" \n\t　"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question");
    }

    @Test
    void rw02到06_合法JSON保序去空去重且空数组使用改写问题() {
        var result = serviceReturning("""
                {"rewrittenQuestion":"  退款需要多久？  ",
                 "subQuestions":[" 材料有哪些？ ","","材料有哪些？","退款多久？"]}
                """).rewrite(new RewriteRequest("摘要", "历史", "原问题"));

        assertThat(result.mode()).isEqualTo(RewriteMode.MODEL);
        assertThat(result.rewrittenQuestion()).isEqualTo("退款需要多久？");
        assertThat(result.subQuestions()).containsExactly("材料有哪些？", "退款多久？");
        assertThat(result.disposition()).isEqualTo(RewriteDisposition.READY);

        var empty = serviceReturning("""
                {"rewrittenQuestion":"改写后的单问题","subQuestions":[]}
                """).rewrite(new RewriteRequest("", "", "原问题"));
        assertThat(empty.subQuestions()).containsExactly("改写后的单问题");
    }

    @Test
    void rw03到04_单层CodeFence可剥离而非法结构安全降级() {
        var fenced = serviceReturning("""
                ```JSON
                {"rewrittenQuestion":"改写","subQuestions":["问题一"]}
                ```
                """).rewrite(new RewriteRequest("", "", "  原始　问题 "));
        assertThat(fenced.subQuestions()).containsExactly("问题一");

        for (String invalid : List.of(
                "not-json",
                "[]",
                "{\"rewrittenQuestion\":\"\",\"subQuestions\":[]}",
                "{\"rewrittenQuestion\":\"改写\",\"subQuestions\":[7]}")) {
            var fallback = serviceReturning(invalid).rewrite(new RewriteRequest("", "", "  原始　问题 "));
            assertThat(fallback.mode()).isEqualTo(RewriteMode.NORMALIZED_FALLBACK);
            assertThat(fallback.subQuestions()).containsExactly("原始 问题");
            assertThat(fallback.degradationCode()).isEqualTo("REWRITE_INVALID_RESPONSE");
        }
    }

    @Test
    void rw07_四个主题显式拒绝而不截断() {
        var result = serviceReturning("""
                {"rewrittenQuestion":"复合问题","subQuestions":["一","二","三","四"]}
                """).rewrite(new RewriteRequest("", "", "复合问题"));

        assertThat(result.disposition()).isEqualTo(RewriteDisposition.TOO_MANY_TOPICS);
        assertThat(result.subQuestions()).containsExactly("一", "二", "三", "四");
    }

    @Test
    void rw08到11_模型失败使用归一化问题并保留逻辑调用ID() {
        UUID logicalCallId = UUID.randomUUID();
        ChatClient failure = command -> {
            throw modelFailure(logicalCallId);
        };

        var result = service(failure).rewrite(new RewriteRequest("摘要", "历史", " Ａ　问题 "));

        assertThat(result.mode()).isEqualTo(RewriteMode.NORMALIZED_FALLBACK);
        assertThat(result.subQuestions()).containsExactly("A 问题");
        assertThat(result.logicalCallId()).isEqualTo(logicalCallId);
        assertThat(result.degradationCode()).isEqualTo("REWRITE_MODEL_UNAVAILABLE");
    }

    @Test
    void rw10到12_Prompt只包含显式上下文且命令固定Balanced() {
        var captured = new AtomicReference<ChatCommand>();
        ChatClient client = command -> {
            captured.set(command);
            return success("{\"rewrittenQuestion\":\"改写\",\"subQuestions\":[\"改写\"]}");
        };

        var result = service(client).rewrite(new RewriteRequest("长期摘要", "近期历史", "当前问题"));

        assertThat(result.logicalCallId()).isNotNull();
        assertThat(captured.get().tier()).isEqualTo(ServiceTier.BALANCED);
        assertThat(captured.get().tools()).isEmpty();
        String userPrompt = ((UserMessage) captured.get().messages().get(1)).text();
        assertThat(userPrompt).contains("长期摘要", "近期历史", "当前问题");
        assertThat(result.getClass().getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("rawResponse", "reason", "prompt");
    }

    private static QueryRewriteService serviceReturning(String response) {
        return service(command -> success(response));
    }

    private static QueryRewriteService service(ChatClient client) {
        return new QueryRewriteService(
                client,
                PromptTemplateCatalog.fromClasspath(65_536),
                new ObjectMapper());
    }

    private static ChatResult success(String text) {
        return new ChatResult(
                new AssistantMessage(text, null, List.of()),
                ChatFinishReason.STOP,
                new TokenUsage(1, 1, 2),
                execution(UUID.randomUUID()));
    }

    private static ModelCallException modelFailure(UUID logicalCallId) {
        return new ModelCallException(
                "模型暂不可用",
                execution(logicalCallId),
                new TerminalCause(
                        ModelErrorType.TIMEOUT,
                        FailurePhase.TOTAL_DEADLINE,
                        TerminationReason.DEADLINE_EXCEEDED,
                        null,
                        null),
                true);
    }

    private static ExecutionInfo execution(UUID logicalCallId) {
        return new ExecutionInfo(
                logicalCallId,
                new RequestId("request-test"),
                new ExecutionTarget(
                        new ProviderId("provider-a"),
                        new DeploymentId("deployment-a"),
                        new ModelIdentity("model-a")),
                1,
                0,
                0);
    }
}
