package com.app.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.app.ai.diagnostic.CompletedCallDiagnostic;
import com.app.ai.diagnostic.DiagnosticRecorder;
import com.app.ai.model.AiRuntimePolicy;
import com.app.ai.model.CallOptions;
import com.app.ai.model.Capability;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.ChatStreamEvent;
import com.app.ai.model.DataRegion;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelCallException;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderConfigurationStatus;
import com.app.ai.model.ProviderId;
import com.app.ai.model.QuotaDomain;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.StreamingChatClient;
import com.app.ai.model.TokenUsage;
import com.app.ai.model.UserMessage;
import com.app.ai.provider.AttemptFailureType;
import com.app.ai.provider.ProgrammableFakeProvider;
import com.app.ai.provider.ProviderChatEvent;
import com.app.ai.provider.ProviderFailureException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class SingleCandidateStreamingChatClientTests {

    @Test
    void str20和21_首个有效块提交后依次返回启动增量和类型化完成事件() {
        var usage = new TokenUsage(5, 2, 7);
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.just(
                        new ProviderChatEvent.TextDelta("你"),
                        new ProviderChatEvent.TextDelta("好"),
                        new ProviderChatEvent.Completed(ChatFinishReason.STOP, usage))));
        StreamingChatClient client = client(fake, new CapturingDiagnosticRecorder());

        List<ChatStreamEvent> events = client.stream(command()).collectList().block();

        assertThat(events).hasSize(4);
        assertThat(events.get(0)).isInstanceOf(ChatStreamEvent.StreamStarted.class);
        assertThat(events.get(1)).isEqualTo(new ChatStreamEvent.TextDelta("你"));
        assertThat(events.get(2)).isEqualTo(new ChatStreamEvent.TextDelta("好"));
        assertThat(events.get(3)).isEqualTo(new ChatStreamEvent.Completed(ChatFinishReason.STOP, usage));
        var started = (ChatStreamEvent.StreamStarted) events.get(0);
        assertThat(started.execution().actualExecution().deploymentId()).isEqualTo(DEPLOYMENT_ID);
        assertThat(started.execution().attemptCount()).isEqualTo(1);
    }

    @Test
    void str27_同一Publisher只允许一次订阅且第二次不创建Provider调用() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.just(
                        new ProviderChatEvent.TextDelta("完成"),
                        new ProviderChatEvent.Completed(ChatFinishReason.STOP, null))));
        StreamingChatClient client = client(fake, new CapturingDiagnosticRecorder());
        Flux<ChatStreamEvent> stream = client.stream(command());

        assertThat(fake.invocations()).isEmpty();
        assertThat(stream.collectList().block()).hasSize(3);
        Throwable secondFailure = catchThrowable(() -> stream.collectList().block());

        assertThat(secondFailure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stream publisher allows only one subscription");
        assertThat(fake.invocations()).hasSize(1);
    }

    @Test
    void str29_Reactor重试被单订阅守卫拒绝且不重复收费调用() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.error(
                        ProviderFailureException.of(AttemptFailureType.CONNECT_FAILED))));
        StreamingChatClient client = client(fake, new CapturingDiagnosticRecorder());

        Throwable failure = catchThrowable(() -> client.stream(command()).retry(1).blockLast());

        assertThat(failure)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stream publisher allows only one subscription");
        assertThat(fake.invocations()).hasSize(1);
    }

    @Test
    void str10_探测期31个非业务事件后首个文本仍可提交() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.concat(
                        Flux.range(0, 31).map(ignored ->
                                (ProviderChatEvent) new ProviderChatEvent.ProbeSignal(1)),
                        Flux.<ProviderChatEvent>just(
                                new ProviderChatEvent.TextDelta("有效"),
                                new ProviderChatEvent.Completed(ChatFinishReason.STOP, null)))));

        List<ChatStreamEvent> events = client(fake, new CapturingDiagnosticRecorder())
                .stream(command())
                .collectList()
                .block();

        assertThat(events).containsExactly(
                events.get(0),
                new ChatStreamEvent.TextDelta("有效"),
                new ChatStreamEvent.Completed(ChatFinishReason.STOP, null));
        assertThat(events.get(0)).isInstanceOf(ChatStreamEvent.StreamStarted.class);
    }

    @Test
    void str11_探测事件达到32个时提交前协议失败且下游零事件() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.range(0, 32)
                        .map(ignored -> (ProviderChatEvent) new ProviderChatEvent.ProbeSignal(1))));
        var events = new ArrayList<ChatStreamEvent>();
        var failure = new AtomicReference<Throwable>();

        client(fake, new CapturingDiagnosticRecorder())
                .stream(command())
                .subscribe(events::add, failure::set);

        assertThat(events).isEmpty();
        assertThat(failure.get()).isInstanceOf(ModelCallException.class);
        var modelFailure = (ModelCallException) failure.get();
        assertThat(modelFailure.terminalCause().errorType())
                .isEqualTo(ModelErrorType.PROTOCOL_CONTRACT);
    }

    @Test
    void str12_探测字节达到64KiB时提交前协议失败() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.just(
                        new ProviderChatEvent.ProbeSignal(65_536),
                        new ProviderChatEvent.TextDelta("不应提交"))));

        Throwable failure = catchThrowable(() -> client(fake, new CapturingDiagnosticRecorder())
                .stream(command())
                .blockLast());

        assertThat(failure).isInstanceOf(ModelCallException.class);
        assertThat(((ModelCallException) failure).terminalCause().errorType())
                .isEqualTo(ModelErrorType.PROTOCOL_CONTRACT);
    }

    @Test
    void str13_有效块前完成属于空流且不发送启动事件() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.just(
                        new ProviderChatEvent.Completed(ChatFinishReason.STOP, null))));
        var events = new ArrayList<ChatStreamEvent>();
        var failure = new AtomicReference<Throwable>();

        client(fake, new CapturingDiagnosticRecorder())
                .stream(command())
                .subscribe(events::add, failure::set);

        assertThat(events).isEmpty();
        assertThat(failure.get()).isInstanceOf(ModelCallException.class);
        assertThat(((ModelCallException) failure.get()).terminalCause().errorType())
                .isEqualTo(ModelErrorType.PROTOCOL_CONTRACT);
    }

    @Test
    void str15和30_提交后断流只发送一个StreamFailed并正常完成() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.concat(
                        Flux.just(new ProviderChatEvent.TextDelta("部分")),
                        Flux.error(ProviderFailureException.of(
                                AttemptFailureType.UPSTREAM_STREAM_INTERRUPTED)))));

        List<ChatStreamEvent> events = client(fake, new CapturingDiagnosticRecorder())
                .stream(command())
                .collectList()
                .block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0)).isInstanceOf(ChatStreamEvent.StreamStarted.class);
        assertThat(events.get(1)).isEqualTo(new ChatStreamEvent.TextDelta("部分"));
        assertThat(events.get(2)).isInstanceOf(ChatStreamEvent.StreamFailed.class);
        var streamFailure = (ChatStreamEvent.StreamFailed) events.get(2);
        assertThat(streamFailure.incomplete()).isTrue();
        assertThat(streamFailure.cause().errorType()).isEqualTo(ModelErrorType.STREAM_INTERRUPTED);
        assertThat(streamFailure.cause().terminationReason())
                .isEqualTo(com.app.ai.model.TerminationReason.STREAM_INTERRUPTED_AFTER_COMMIT);
    }

    @Test
    void str16_工具增量身份和参数片段按上游顺序直通() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.just(
                        new ProviderChatEvent.ToolCallDelta("call-1", 0, "search", "{\"q\":"),
                        new ProviderChatEvent.ToolCallDelta("call-1", 0, "search", "\"x\"}"),
                        new ProviderChatEvent.Completed(ChatFinishReason.TOOL_CALLS, null))));

        List<ChatStreamEvent> events = client(fake, new CapturingDiagnosticRecorder())
                .stream(command())
                .collectList()
                .block();

        assertThat(events.subList(1, events.size())).containsExactly(
                new ChatStreamEvent.ToolCallDelta("call-1", 0, "search", "{\"q\":"),
                new ChatStreamEvent.ToolCallDelta("call-1", 0, "search", "\"x\"}"),
                new ChatStreamEvent.Completed(ChatFinishReason.TOOL_CALLS, null));
    }

    private static final ProviderId PROVIDER_ID = new ProviderId("provider-a");
    private static final DeploymentId DEPLOYMENT_ID = new DeploymentId("stream-chat-a");

    private static StreamingChatClient client(
            ProgrammableFakeProvider fake, DiagnosticRecorder diagnostics) {
        return new SingleCandidateStreamingChatClient(
                catalog(), DEPLOYMENT_ID, fake, diagnostics);
    }

    private static ChatCommand command() {
        return new ChatCommand(
                List.of(new UserMessage("你好")),
                List.of(),
                ServiceTier.BALANCED,
                CallOptions.defaults());
    }

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
                Set.of(Capability.STREAM_CHAT),
                Set.of(ServiceTier.BALANCED),
                true,
                10,
                true,
                null);
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
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(120), 3, 1),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofMinutes(15), 3, 2),
                new AiRuntimePolicy.StreamLimits(32, 65_536, 65_536),
                new AiRuntimePolicy.BreakerLimits(
                        20, 5, 50, 50, Duration.ofSeconds(30), 2, Duration.ofSeconds(30)));
    }

    private static final class CapturingDiagnosticRecorder implements DiagnosticRecorder {
        private final List<CompletedCallDiagnostic> calls = new ArrayList<>();

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
