package com.app.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.ai.diagnostic.AttemptOutcome;
import com.app.ai.diagnostic.CallOutcome;
import com.app.ai.diagnostic.CompletedCallDiagnostic;
import com.app.ai.diagnostic.DiagnosticRecorder;
import com.app.ai.model.AiRuntimePolicy;
import com.app.ai.model.AssistantMessage;
import com.app.ai.model.CallOptions;
import com.app.ai.model.Capability;
import com.app.ai.model.CancellationRegistration;
import com.app.ai.model.CancellationToken;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.ChatProfile;
import com.app.ai.model.ChatResult;
import com.app.ai.model.ChatSamplingParameters;
import com.app.ai.model.DataRegion;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ModelCallException;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderConfigurationStatus;
import com.app.ai.model.ProviderId;
import com.app.ai.model.QuotaDomain;
import com.app.ai.model.RequestId;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.TokenUsage;
import com.app.ai.model.ToolCall;
import com.app.ai.model.ToolDefinition;
import com.app.ai.model.UserMessage;
import com.app.ai.provider.ProgrammableFakeProvider;
import com.app.ai.provider.ChatProvider;
import com.app.ai.provider.ProviderFailureException;
import com.app.ai.provider.ProviderChatResult;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SingleCandidateChatClientTests {

    @Test
    void syn01_单候选200返回类型化结果执行事实和完成态诊断() {
        var expectedMessage = new AssistantMessage("完成", null, List.of());
        var expectedUsage = new TokenUsage(12, 5, 17);
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> new ProviderChatResult(
                        expectedMessage,
                        ChatFinishReason.STOP,
                        expectedUsage,
                        "req_****9af2")));
        var diagnostics = new CapturingDiagnosticRecorder();
        var client = new SingleCandidateChatClient(
                catalog(), DEPLOYMENT_ID, fake, diagnostics);
        var command = new ChatCommand(
                List.of(new UserMessage("你好")),
                List.of(),
                ServiceTier.BALANCED,
                new CallOptions(new RequestId("request-42"), null, null, Set.of()));

        ChatResult result = client.complete(command);

        assertThat(result.message()).isEqualTo(expectedMessage);
        assertThat(result.finishReason()).isEqualTo(ChatFinishReason.STOP);
        assertThat(result.usage()).isEqualTo(expectedUsage);
        assertThat(result.execution().requestId()).isEqualTo(new RequestId("request-42"));
        assertThat(result.execution().actualExecution().deploymentId()).isEqualTo(DEPLOYMENT_ID);
        assertThat(result.execution().attemptCount()).isEqualTo(1);
        assertThat(result.execution().retryCount()).isZero();
        assertThat(result.execution().failoverCount()).isZero();

        assertThat(diagnostics.calls).hasSize(1);
        CompletedCallDiagnostic diagnostic = diagnostics.calls.get(0);
        assertThat(diagnostic.logicalCallId()).isEqualTo(result.execution().logicalCallId());
        assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.SUCCEEDED);
        assertThat(diagnostic.candidates()).hasSize(1);
        assertThat(diagnostic.attempts()).hasSize(1);
        assertThat(diagnostic.attempts().get(0).outcome()).isEqualTo(AttemptOutcome.SUCCEEDED);
        assertThat(diagnostic.executionSummary().actualExecution())
                .isEqualTo(result.execution().actualExecution());
        assertThat(diagnostic.finishReason()).isEqualTo(ChatFinishReason.STOP);
        assertThat(diagnostic.usage().inputTokenCount()).isEqualTo(12);
        assertThat(diagnostic.routeEvents().get(diagnostic.routeEvents().size() - 1).reason())
                .isEqualTo("COMPLETED");
    }

    @Test
    void syn06和17_Provider失败只暴露统一异常并保留失败attempt诊断() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    throw ProviderFailureException.http(401, null, "req_****9af2");
                }));
        var diagnostics = new CapturingDiagnosticRecorder();
        var client = new SingleCandidateChatClient(catalog(), DEPLOYMENT_ID, fake, diagnostics);
        var command = new ChatCommand(
                List.of(new UserMessage("secret-prompt-marker")),
                List.of(),
                ServiceTier.BALANCED,
                CallOptions.defaults());

        var failure = catchThrowableOfType(ModelCallException.class, () -> client.complete(command));

        assertThat(failure.getMessage())
                .isEqualTo("模型供应商认证失败")
                .doesNotContain("secret-prompt-marker", "Authorization", "raw");
        assertThat(failure.getCause()).isNull();
        assertThat(failure.execution().actualExecution()).isNull();
        assertThat(failure.execution().attemptCount()).isEqualTo(1);
        assertThat(failure.terminalCause().errorType()).isEqualTo(ModelErrorType.AUTHENTICATION);
        assertThat(failure.terminalCause().failurePhase())
                .isEqualTo(com.app.ai.model.FailurePhase.RESPONSE_HEADERS);
        assertThat(failure.terminalCause().terminationReason())
                .isEqualTo(com.app.ai.model.TerminationReason.NON_RECOVERABLE_ERROR);
        assertThat(failure.terminalCause().attemptedExecution().deploymentId())
                .isEqualTo(DEPLOYMENT_ID);
        assertThat(failure.retryableByCaller()).isFalse();

        assertThat(diagnostics.calls).hasSize(1);
        var diagnostic = diagnostics.calls.get(0);
        assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.FAILED);
        assertThat(diagnostic.normalizedErrorType()).isEqualTo(ModelErrorType.AUTHENTICATION);
        assertThat(diagnostic.attempts()).hasSize(1);
        assertThat(diagnostic.attempts().get(0).outcome()).isEqualTo(AttemptOutcome.FAILED);
        assertThat(diagnostic.executionSummary().actualExecution()).isNull();
    }

    @Test
    void syn29_工具调用和工具定义在Provider调用前拒绝非法公开形状() {
        assertThatThrownBy(() -> new ToolCall(" ", "search", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolCall("call-1", "", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolCall("call-1", "search", "not-json"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDefinition(" ", "搜索", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ToolDefinition("search", "搜索", "[]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void syn25_预取消在出站调度前结束且不创建attempt() {
        CancellationToken canceled = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public CancellationRegistration onCancel(Runnable listener) {
                listener.run();
                return () -> { };
            }
        };
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    throw new AssertionError("pre-canceled call must not reach scripted dispatch");
                }));
        var diagnostics = new CapturingDiagnosticRecorder();
        var client = new SingleCandidateChatClient(catalog(), DEPLOYMENT_ID, fake, diagnostics);
        var command = new ChatCommand(
                List.of(new UserMessage("你好")),
                List.of(),
                ServiceTier.BALANCED,
                new CallOptions(null, null, canceled, Set.of()));

        var failure = catchThrowableOfType(ModelCallException.class, () -> client.complete(command));

        assertThat(failure.terminalCause().errorType()).isEqualTo(ModelErrorType.CANCELED);
        assertThat(failure.execution().attemptCount()).isZero();
        assertThat(failure.terminalCause().attemptedExecution()).isNull();
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.CANCELED);
            assertThat(diagnostic.attempts()).isEmpty();
        });
    }

    @Test
    void syn16_诊断记录器异常不改变成功结果() {
        var expected = new ProviderChatResult(
                new AssistantMessage("完成", null, List.of()),
                ChatFinishReason.STOP,
                null,
                null);
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> expected));
        DiagnosticRecorder brokenRecorder = new DiagnosticRecorder() {
            @Override
            public boolean offer(CompletedCallDiagnostic diagnostic) {
                throw new IllegalStateException("diagnostic-store-unavailable");
            }

            @Override
            public long droppedCount() {
                return 0;
            }

            @Override
            public long writeFailureCount() {
                return 0;
            }
        };
        var client = new SingleCandidateChatClient(catalog(), DEPLOYMENT_ID, fake, brokenRecorder);

        ChatResult result = client.complete(new ChatCommand(
                List.of(new UserMessage("你好")), List.of(), ServiceTier.BALANCED, CallOptions.defaults()));

        assertThat(result.message()).isEqualTo(expected.message());
        assertThat(result.execution().attemptCount()).isEqualTo(1);
    }

    @Test
    void provider未声明出站调度时归一为协议错误且attempt为零() {
        ChatProvider invalidProvider = (deployment, request, context) -> new ProviderChatResult(
                new AssistantMessage("不应返回", null, List.of()),
                ChatFinishReason.STOP,
                null,
                null);
        var diagnostics = new CapturingDiagnosticRecorder();
        var client = new SingleCandidateChatClient(
                catalog(), DEPLOYMENT_ID, invalidProvider, diagnostics);

        var failure = catchThrowableOfType(
                ModelCallException.class,
                () -> client.complete(new ChatCommand(
                        List.of(new UserMessage("你好")),
                        List.of(),
                        ServiceTier.BALANCED,
                        CallOptions.defaults())));

        assertThat(failure.terminalCause().errorType()).isEqualTo(ModelErrorType.PROTOCOL_CONTRACT);
        assertThat(failure.execution().attemptCount()).isZero();
        assertThat(diagnostics.calls).singleElement()
                .satisfies(diagnostic -> assertThat(diagnostic.attempts()).isEmpty());
    }

    private static final ProviderId PROVIDER_ID = new ProviderId("provider-a");
    private static final DeploymentId DEPLOYMENT_ID = new DeploymentId("chat-a");

    private static ModelCatalogSnapshot catalog() {
        var provider = new ModelProvider(
                PROVIDER_ID,
                "openai-compatible",
                "Provider A",
                URI.create("http://127.0.0.1:1/v1"),
                "TEST_API_KEY",
                new DataRegion("CN_TEST"),
                new QuotaDomain("quota-a"),
                ProviderConfigurationStatus.CONFIGURED);
        var deployment = new ModelDeployment(
                DEPLOYMENT_ID,
                PROVIDER_ID,
                "chat-model",
                new ModelIdentity("chat-model-v1"),
                Set.of(Capability.CHAT),
                Set.of(ServiceTier.BALANCED),
                true,
                10,
                true,
                null,
                Set.of(),
                null,
                new ChatProfile(Map.of(
                        ServiceTier.BALANCED, new ChatSamplingParameters(0.1, 0.3))));
        return new ModelCatalogSnapshot(
                "test-v1",
                "a".repeat(64),
                Map.of(PROVIDER_ID, provider),
                Map.of(DEPLOYMENT_ID, deployment),
                runtimePolicy());
    }

    private static AiRuntimePolicy runtimePolicy() {
        return new AiRuntimePolicy(
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(45), 3, 2),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(120), 3, 1),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofMinutes(15), 3, 2),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(20), 3, 2),
                new AiRuntimePolicy.StreamLimits(32, 65_536, 65_536),
                new AiRuntimePolicy.BreakerLimits(
                        20, 5, 50, 50, Duration.ofSeconds(30), 2, Duration.ofSeconds(30)));
    }

    private static final class CapturingDiagnosticRecorder implements DiagnosticRecorder {
        private final List<CompletedCallDiagnostic> calls = new java.util.ArrayList<>();

        @Override
        public boolean offer(CompletedCallDiagnostic diagnostic) {
            calls.add(diagnostic);
            return true;
        }

        @Override
        public long droppedCount() {
            return 0;
        }

        @Override
        public long writeFailureCount() {
            return 0;
        }
    }
}
