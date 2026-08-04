package com.app.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.app.ai.diagnostic.CompletedCallDiagnostic;
import com.app.ai.diagnostic.DiagnosticRecorder;
import com.app.ai.diagnostic.AttemptOutcome;
import com.app.ai.diagnostic.CallOutcome;
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
import com.app.ai.model.FailurePhase;
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
import com.app.ai.provider.StreamingChatProvider;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import org.reactivestreams.Subscription;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.BaseSubscriber;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.scheduler.VirtualTimeScheduler;

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
    void str30_Reactor重试被单订阅守卫拒绝且不重复收费调用() {
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
    void 探测上限前31个非业务事件后首个文本仍可提交() {
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
    void str10_探测事件达到32个时提交前协议失败且下游零事件() {
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
    void 探测字节达到64KiB时提交前协议失败() {
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
    void str11_只有空Delta后完成属于空流且attempt标记EmptyStream() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.just(
                        new ProviderChatEvent.TextDelta(""),
                        new ProviderChatEvent.Completed(ChatFinishReason.STOP, null))));
        var events = new ArrayList<ChatStreamEvent>();
        var failure = new AtomicReference<Throwable>();
        var diagnostics = new CapturingDiagnosticRecorder();

        client(fake, diagnostics)
                .stream(command())
                .subscribe(events::add, failure::set);

        assertThat(events).isEmpty();
        assertThat(failure.get()).isInstanceOf(ModelCallException.class);
        assertThat(((ModelCallException) failure.get()).terminalCause().errorType())
                .isEqualTo(ModelErrorType.PROTOCOL_CONTRACT);
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.attempts()).singleElement().satisfies(attempt ->
                        assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.EMPTY_STREAM)));
    }

    @Test
    void str18和29_提交后断流只发送一个StreamFailed并正常完成() {
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
    void 提交前断流按是否已收到合法事件区分首事件和首有效阶段() {
        var beforeEvent = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.error(
                        ProviderFailureException.of(
                                AttemptFailureType.UPSTREAM_STREAM_INTERRUPTED))));

        Throwable beforeFailure = catchThrowable(() ->
                client(beforeEvent, new CapturingDiagnosticRecorder()).stream(command()).blockLast());

        assertThat(((ModelCallException) beforeFailure).terminalCause().failurePhase())
                .isEqualTo(FailurePhase.FIRST_UPSTREAM_EVENT);

        var duringProbe = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.concat(
                        Flux.just(new ProviderChatEvent.ProbeSignal(1)),
                        Flux.error(ProviderFailureException.of(
                                AttemptFailureType.UPSTREAM_STREAM_INTERRUPTED)))));

        Throwable probeFailure = catchThrowable(() ->
                client(duringProbe, new CapturingDiagnosticRecorder()).stream(command()).blockLast());

        assertThat(((ModelCallException) probeFailure).terminalCause().failurePhase())
                .isEqualTo(FailurePhase.FIRST_MEANINGFUL_CHUNK);
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

    @Test
    void str17_首个有效内容为拒答时同样形成提交点() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.just(
                        new ProviderChatEvent.RefusalDelta("不能回答"),
                        new ProviderChatEvent.Completed(ChatFinishReason.CONTENT_FILTER, null))));

        List<ChatStreamEvent> events = client(fake, new CapturingDiagnosticRecorder())
                .stream(command())
                .collectList()
                .block();

        assertThat(events.get(0)).isInstanceOf(ChatStreamEvent.StreamStarted.class);
        assertThat(events.subList(1, events.size())).containsExactly(
                new ChatStreamEvent.RefusalDelta("不能回答"),
                new ChatStreamEvent.Completed(ChatFinishReason.CONTENT_FILTER, null));
    }

    @Test
    void str19_提交后ProviderError映射为ServerError且不暴露Reactor错误() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.concat(
                        Flux.just(new ProviderChatEvent.TextDelta("部分")),
                        Flux.error(ProviderFailureException.of(
                                AttemptFailureType.UPSTREAM_ERROR_EVENT)))));

        List<ChatStreamEvent> events = client(fake, new CapturingDiagnosticRecorder())
                .stream(command())
                .collectList()
                .block();

        assertThat(events).hasSize(3);
        assertThat(events.get(2)).isInstanceOfSatisfying(
                ChatStreamEvent.StreamFailed.class,
                event -> {
                    assertThat(event.cause().errorType()).isEqualTo(ModelErrorType.SERVER_ERROR);
                    assertThat(event.cause().failurePhase()).isEqualTo(FailurePhase.UPSTREAM_STREAM);
                });
    }

    @Test
    void str20_成功流只结算一次完成态诊断并记录三个时间点() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.just(
                        new ProviderChatEvent.ProbeSignal(1),
                        new ProviderChatEvent.TextDelta("完成"),
                        new ProviderChatEvent.Completed(ChatFinishReason.STOP, null))));
        var diagnostics = new CapturingDiagnosticRecorder();

        client(fake, diagnostics).stream(command()).blockLast();

        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.SUCCEEDED);
            assertThat(diagnostic.committed()).isTrue();
            assertThat(diagnostic.attempts()).singleElement().satisfies(attempt -> {
                assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.SUCCEEDED);
                assertThat(attempt.firstUpstreamEventMs()).isNotNull();
                assertThat(attempt.firstMeaningfulChunkMs()).isNotNull();
                assertThat(attempt.downstreamCommitMs()).isNotNull();
                assertThat(attempt.streamEventCount()).isEqualTo(3);
            });
        });
    }

    @Test
    void str29_提交后失败只结算一次StreamFailed诊断() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.concat(
                        Flux.just(new ProviderChatEvent.TextDelta("部分")),
                        Flux.error(ProviderFailureException.of(
                                AttemptFailureType.UPSTREAM_STREAM_INTERRUPTED)))));
        var diagnostics = new CapturingDiagnosticRecorder();

        client(fake, diagnostics).stream(command()).blockLast();

        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.STREAM_FAILED);
            assertThat(diagnostic.committed()).isTrue();
            assertThat(diagnostic.attempts()).singleElement().satisfies(attempt ->
                    assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.STREAM_FAILED));
        });
    }

    @Test
    void str23_提交后取消传播且不伪造失败事件并只释放和结算一次() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.concat(
                        Flux.just(new ProviderChatEvent.TextDelta("部分")),
                        Flux.never())));
        var diagnostics = new CapturingDiagnosticRecorder();
        var events = new ArrayList<ChatStreamEvent>();
        var subscription = client(fake, diagnostics).stream(command()).subscribe(events::add);

        subscription.dispose();

        assertThat(events).hasSize(2);
        assertThat(events).noneMatch(ChatStreamEvent.StreamFailed.class::isInstance);
        assertThat(fake.cancelledCount()).isEqualTo(1);
        assertThat(fake.releasedCount()).isEqualTo(1);
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.CANCELED);
            assertThat(diagnostic.committed()).isTrue();
            assertThat(diagnostic.attempts()).singleElement().satisfies(attempt ->
                    assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.CANCELED));
        });
    }

    @Test
    void str22_提交前取消关闭上游且诊断保持未提交() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.never()));
        var diagnostics = new CapturingDiagnosticRecorder();
        var events = new ArrayList<ChatStreamEvent>();
        var subscription = client(fake, diagnostics).stream(command()).subscribe(events::add);

        subscription.dispose();

        assertThat(events).isEmpty();
        assertThat(fake.cancelledCount()).isEqualTo(1);
        assertThat(fake.releasedCount()).isEqualTo(1);
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.CANCELED);
            assertThat(diagnostic.committed()).isFalse();
        });
    }

    @Test
    void str24_取消与完成竞态循环中终态诊断和资源释放始终唯一() {
        for (int iteration = 0; iteration < 50; iteration++) {
            Sinks.Many<ProviderChatEvent> terminal = Sinks.many().unicast().onBackpressureBuffer();
            var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                    (deployment, request, context) -> Flux.concat(
                            Flux.just(new ProviderChatEvent.TextDelta("部分")),
                            terminal.asFlux())));
            var diagnostics = new CapturingDiagnosticRecorder();
            var events = new ArrayList<ChatStreamEvent>();
            var subscription = client(fake, diagnostics).stream(command()).subscribe(events::add);

            CompletableFuture<Void> complete = CompletableFuture.runAsync(() -> {
                terminal.tryEmitNext(new ProviderChatEvent.Completed(ChatFinishReason.STOP, null));
                terminal.tryEmitComplete();
            });
            CompletableFuture<Void> cancel = CompletableFuture.runAsync(subscription::dispose);
            CompletableFuture.allOf(complete, cancel).join();

            assertThat(diagnostics.calls).hasSize(1);
            assertThat(fake.releasedCount()).isEqualTo(1);
            assertThat(events.stream()
                            .filter(event -> event instanceof ChatStreamEvent.Completed
                                    || event instanceof ChatStreamEvent.StreamFailed)
                            .count())
                    .isLessThanOrEqualTo(1);
        }
    }

    @Test
    void str25_慢下游不会让状态机向上游请求无界数据且取消后释放连接() {
        var largestRequest = new AtomicLong();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.<ProviderChatEvent>never()
                        .doOnRequest(requested -> largestRequest.accumulateAndGet(
                                requested, Math::max))));
        var subscriber = new BaseSubscriber<ChatStreamEvent>() {
            @Override
            protected void hookOnSubscribe(Subscription subscription) {
                request(1);
            }
        };

        client(fake, new CapturingDiagnosticRecorder()).stream(command()).subscribe(subscriber);

        assertThat(largestRequest.get()).isPositive().isLessThan(Long.MAX_VALUE);
        subscriber.dispose();
        assertThat(fake.releasedCount()).isEqualTo(1);
    }

    @Test
    void str12_首个上游事件超时在提交前按独立阶段失败() {
        var scheduler = VirtualTimeScheduler.create();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.never()));
        var failure = new AtomicReference<Throwable>();

        client(fake, new CapturingDiagnosticRecorder(), scheduler)
                .stream(command())
                .subscribe(ignored -> { }, failure::set);
        scheduler.advanceTimeBy(Duration.ofSeconds(15));

        assertThat(failure.get()).isInstanceOf(ModelCallException.class);
        assertThat(((ModelCallException) failure.get()).terminalCause().failurePhase())
                .isEqualTo(FailurePhase.FIRST_UPSTREAM_EVENT);
        assertThat(fake.releasedCount()).isEqualTo(1);
    }

    @Test
    void str26_连接和响应头分别按独立阶段超时() {
        var connectScheduler = VirtualTimeScheduler.create();
        StreamingChatProvider neverConnected = (deployment, request, context) -> Flux.defer(() -> {
            context.lifecycle().dispatched();
            return Flux.never();
        });
        var connectFailure = new AtomicReference<Throwable>();
        client(neverConnected, new CapturingDiagnosticRecorder(), connectScheduler)
                .stream(command())
                .subscribe(ignored -> { }, connectFailure::set);

        connectScheduler.advanceTimeBy(Duration.ofSeconds(3));

        assertThat(connectFailure.get()).isInstanceOf(ModelCallException.class);
        assertThat(((ModelCallException) connectFailure.get()).terminalCause().failurePhase())
                .isEqualTo(FailurePhase.CONNECT);

        var headersScheduler = VirtualTimeScheduler.create();
        StreamingChatProvider noHeaders = (deployment, request, context) -> Flux.defer(() -> {
            context.lifecycle().dispatched();
            context.lifecycle().connected();
            return Flux.never();
        });
        var headersFailure = new AtomicReference<Throwable>();
        client(noHeaders, new CapturingDiagnosticRecorder(), headersScheduler)
                .stream(command())
                .subscribe(ignored -> { }, headersFailure::set);

        headersScheduler.advanceTimeBy(Duration.ofSeconds(10));

        assertThat(headersFailure.get()).isInstanceOf(ModelCallException.class);
        assertThat(((ModelCallException) headersFailure.get()).terminalCause().failurePhase())
                .isEqualTo(FailurePhase.RESPONSE_HEADERS);
    }

    @Test
    void str13_收到心跳但没有有效块时按首有效块阶段超时() {
        var scheduler = VirtualTimeScheduler.create();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.concat(
                        Flux.just(new ProviderChatEvent.ProbeSignal(1)),
                        Flux.never())));
        var failure = new AtomicReference<Throwable>();

        client(fake, new CapturingDiagnosticRecorder(), scheduler)
                .stream(command())
                .subscribe(ignored -> { }, failure::set);
        scheduler.advanceTimeBy(Duration.ofSeconds(20));

        assertThat(failure.get()).isInstanceOf(ModelCallException.class);
        assertThat(((ModelCallException) failure.get()).terminalCause().failurePhase())
                .isEqualTo(FailurePhase.FIRST_MEANINGFUL_CHUNK);
    }

    @Test
    void str26_提交后有效块空闲超时返回唯一StreamFailed并正常结束() {
        var scheduler = VirtualTimeScheduler.create();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.concat(
                        Flux.just(new ProviderChatEvent.TextDelta("部分")),
                        Flux.never())));
        var events = new ArrayList<ChatStreamEvent>();
        var completed = new AtomicBoolean();

        client(fake, new CapturingDiagnosticRecorder(), scheduler)
                .stream(command())
                .subscribe(events::add, ignored -> { }, () -> completed.set(true));
        scheduler.advanceTimeBy(Duration.ofSeconds(30));

        assertThat(events).hasSize(3);
        assertThat(events.get(2)).isInstanceOfSatisfying(
                ChatStreamEvent.StreamFailed.class,
                event -> assertThat(event.cause().failurePhase())
                        .isEqualTo(FailurePhase.STREAM_IDLE));
        assertThat(completed).isTrue();
        assertThat(fake.releasedCount()).isEqualTo(1);
    }

    @Test
    void str35_总截止时间在提交前后都具有唯一终态和固定优先级() {
        var preScheduler = VirtualTimeScheduler.create();
        var preFake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.never()));
        var preFailure = new AtomicReference<Throwable>();
        client(preFake, new CapturingDiagnosticRecorder(), preScheduler)
                .stream(command(Duration.ofSeconds(4)))
                .subscribe(ignored -> { }, preFailure::set);

        preScheduler.advanceTimeBy(Duration.ofSeconds(4));

        assertThat(preFailure.get()).isInstanceOf(ModelCallException.class);
        assertThat(((ModelCallException) preFailure.get()).terminalCause().failurePhase())
                .isEqualTo(FailurePhase.TOTAL_DEADLINE);

        var postScheduler = VirtualTimeScheduler.create();
        var postFake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.concat(
                        Flux.just(new ProviderChatEvent.TextDelta("部分")),
                        Flux.never())));
        var postEvents = new ArrayList<ChatStreamEvent>();
        var postCompleted = new AtomicBoolean();
        client(postFake, new CapturingDiagnosticRecorder(), postScheduler)
                .stream(command(Duration.ofSeconds(4)))
                .subscribe(postEvents::add, ignored -> { }, () -> postCompleted.set(true));

        postScheduler.advanceTimeBy(Duration.ofSeconds(4));

        assertThat(postEvents).hasSize(3);
        assertThat(postEvents.get(2)).isInstanceOfSatisfying(
                ChatStreamEvent.StreamFailed.class,
                event -> {
                    assertThat(event.cause().failurePhase()).isEqualTo(FailurePhase.TOTAL_DEADLINE);
                    assertThat(event.cause().terminationReason())
                            .isEqualTo(com.app.ai.model.TerminationReason.STREAM_INTERRUPTED_AFTER_COMMIT);
                });
        assertThat(postCompleted).isTrue();
    }

    private static final ProviderId PROVIDER_ID = new ProviderId("provider-a");
    private static final DeploymentId DEPLOYMENT_ID = new DeploymentId("stream-chat-a");

    private static StreamingChatClient client(
            StreamingChatProvider provider, DiagnosticRecorder diagnostics) {
        return new SingleCandidateStreamingChatClient(
                catalog(), DEPLOYMENT_ID, provider, diagnostics);
    }

    private static StreamingChatClient client(
            StreamingChatProvider provider,
            DiagnosticRecorder diagnostics,
            VirtualTimeScheduler scheduler) {
        return new SingleCandidateStreamingChatClient(
                catalog(),
                DEPLOYMENT_ID,
                provider,
                diagnostics,
                StreamPhaseTimeouts.defaults(),
                scheduler);
    }

    private static ChatCommand command() {
        return new ChatCommand(
                List.of(new UserMessage("你好")),
                List.of(),
                ServiceTier.BALANCED,
                CallOptions.defaults());
    }

    private static ChatCommand command(Duration timeout) {
        return new ChatCommand(
                List.of(new UserMessage("你好")),
                List.of(),
                ServiceTier.BALANCED,
                new CallOptions(null, timeout, null, Set.of()));
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
