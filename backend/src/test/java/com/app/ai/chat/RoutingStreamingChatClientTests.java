package com.app.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.breaker.BreakerPermission;
import com.app.ai.breaker.Resilience4jModelBreakerRegistry;
import com.app.ai.diagnostic.AttemptKind;
import com.app.ai.diagnostic.AttemptOutcome;
import com.app.ai.diagnostic.BreakerState;
import com.app.ai.diagnostic.CallOutcome;
import com.app.ai.diagnostic.CompletedCallDiagnostic;
import com.app.ai.diagnostic.DiagnosticRecorder;
import com.app.ai.diagnostic.RouteEventType;
import com.app.ai.model.AiRuntimePolicy;
import com.app.ai.model.CallOptions;
import com.app.ai.model.Capability;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.ChatStreamEvent;
import com.app.ai.model.DataRegion;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.FailurePhase;
import com.app.ai.model.ModelCallException;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderConfigurationStatus;
import com.app.ai.model.ProviderId;
import com.app.ai.model.QuotaDomain;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.StreamingChatClient;
import com.app.ai.model.TerminationReason;
import com.app.ai.model.UserMessage;
import com.app.ai.provider.AttemptFailureType;
import com.app.ai.provider.ProgrammableFakeProvider;
import com.app.ai.provider.ProviderChatEvent;
import com.app.ai.provider.ProviderFailureException;
import com.app.ai.provider.StreamingChatProvider;
import com.app.ai.routing.RoutePlanner;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.scheduler.VirtualTimeScheduler;

class RoutingStreamingChatClientTests {

    private static final ProviderId PROVIDER_A = new ProviderId("provider-a");
    private static final ProviderId PROVIDER_B = new ProviderId("provider-b");
    private static final DeploymentId STREAM_A = new DeploymentId("stream-a");
    private static final DeploymentId STREAM_B = new DeploymentId("stream-b");

    @Test
    void 单候选成功流经路由层提交并以成功结算breaker() {
        var catalog = catalog();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.just(
                        new ProviderChatEvent.TextDelta("你好"),
                        new ProviderChatEvent.Completed(ChatFinishReason.STOP, null))));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        StreamingChatClient client = new RoutingStreamingChatClient(
                catalog, Map.of(PROVIDER_A, fake, PROVIDER_B, fake), breakers, diagnostics);

        List<ChatStreamEvent> events = client.stream(command()).collectList().block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0)).isInstanceOfSatisfying(
                ChatStreamEvent.StreamStarted.class,
                started -> {
                    assertThat(started.execution().actualExecution().deploymentId())
                            .isEqualTo(STREAM_A);
                    assertThat(started.execution().attemptCount()).isEqualTo(1);
                    assertThat(started.execution().failoverCount()).isZero();
                });
        assertThat(events.get(1)).isEqualTo(new ChatStreamEvent.TextDelta("你好"));
        assertThat(events.get(2)).isEqualTo(
                new ChatStreamEvent.Completed(ChatFinishReason.STOP, null));
        assertThat(fake.invocations())
                .extracting(ProgrammableFakeProvider.Invocation::deploymentId)
                .containsExactly(STREAM_A);
        assertThat(breakers.snapshot(target(STREAM_A, PROVIDER_A), Capability.STREAM_CHAT)
                        .bufferedCalls())
                .isEqualTo(1);
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.SUCCEEDED);
            assertThat(diagnostic.committed()).isTrue();
            assertThat(diagnostic.stream()).isTrue();
            assertThat(diagnostic.attempts()).singleElement().satisfies(attempt ->
                    assertThat(attempt.downstreamCommitMs()).isNotNull());
            assertThat(diagnostic.routeEvents()).extracting(event -> event.eventType())
                    .containsExactly(
                            RouteEventType.ROUTE_COMPLETED, RouteEventType.CALL_TERMINATED);
        });
    }

    @Test
    void str14_首选提交前503后切次选成功且下游只看到次选事件() {
        var catalog = catalog();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> deployment.id().equals(STREAM_A)
                        ? Flux.concat(
                                Flux.just(new ProviderChatEvent.ProbeSignal(8)),
                                Flux.error(ProviderFailureException.http(503, null, "req_****a")))
                        : Flux.just(
                                new ProviderChatEvent.TextDelta("次选"),
                                new ProviderChatEvent.Completed(ChatFinishReason.STOP, null))));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        StreamingChatClient client = new RoutingStreamingChatClient(
                catalog, Map.of(PROVIDER_A, fake, PROVIDER_B, fake), breakers, diagnostics);

        List<ChatStreamEvent> events = client.stream(command()).collectList().block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0)).isInstanceOfSatisfying(
                ChatStreamEvent.StreamStarted.class,
                started -> {
                    assertThat(started.execution().actualExecution().deploymentId())
                            .isEqualTo(STREAM_B);
                    assertThat(started.execution().attemptCount()).isEqualTo(2);
                    assertThat(started.execution().failoverCount()).isEqualTo(1);
                });
        assertThat(events.get(1)).isEqualTo(new ChatStreamEvent.TextDelta("次选"));
        assertThat(events.get(2)).isEqualTo(
                new ChatStreamEvent.Completed(ChatFinishReason.STOP, null));
        assertThat(fake.invocations())
                .extracting(ProgrammableFakeProvider.Invocation::deploymentId)
                .containsExactly(STREAM_A, STREAM_B);
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.SUCCEEDED);
            assertThat(diagnostic.attempts()).extracting(attempt -> attempt.attemptKind())
                    .containsExactly(AttemptKind.INITIAL, AttemptKind.FAILOVER);
            assertThat(diagnostic.attempts()).extracting(attempt -> attempt.outcome())
                    .containsExactly(AttemptOutcome.FAILED, AttemptOutcome.SUCCEEDED);
            assertThat(diagnostic.attempts()).first().satisfies(attempt -> {
                assertThat(attempt.normalizedErrorType()).isEqualTo(ModelErrorType.SERVER_ERROR);
                assertThat(attempt.httpStatus()).isEqualTo(503);
                assertThat(attempt.downstreamCommitMs()).isNull();
            });
            assertThat(diagnostic.attempts())
                    .filteredOn(attempt -> attempt.downstreamCommitMs() != null)
                    .hasSize(1);
            assertThat(diagnostic.routeEvents()).extracting(event -> event.eventType())
                    .containsExactly(
                            RouteEventType.ROUTE_COMPLETED,
                            RouteEventType.FAILOVER_SELECTED,
                            RouteEventType.CALL_TERMINATED);
            assertThat(diagnostic.routeEvents().get(1).deploymentId()).isEqualTo(STREAM_B);
        });
    }

    @Test
    void str18与dia06_提交后中断只发唯一StreamFailed且不启动第二个deployment() {
        var catalog = catalog();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> deployment.id().equals(STREAM_A)
                        ? Flux.concat(
                                Flux.just(new ProviderChatEvent.TextDelta("部分")),
                                Flux.error(ProviderFailureException.of(
                                        AttemptFailureType.UPSTREAM_STREAM_INTERRUPTED)))
                        : Flux.just(
                                new ProviderChatEvent.TextDelta("不应出现"),
                                new ProviderChatEvent.Completed(ChatFinishReason.STOP, null))));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        StreamingChatClient client = new RoutingStreamingChatClient(
                catalog, Map.of(PROVIDER_A, fake, PROVIDER_B, fake), breakers, diagnostics);
        var events = new ArrayList<ChatStreamEvent>();
        var completed = new AtomicBoolean();
        var reactorError = new AtomicReference<Throwable>();

        client.stream(command()).subscribe(events::add, reactorError::set, () -> completed.set(true));

        assertThat(reactorError.get()).isNull();
        assertThat(completed).isTrue();
        assertThat(events).hasSize(3);
        assertThat(events.get(0)).isInstanceOf(ChatStreamEvent.StreamStarted.class);
        assertThat(events.get(1)).isEqualTo(new ChatStreamEvent.TextDelta("部分"));
        assertThat(events.get(2)).isInstanceOfSatisfying(
                ChatStreamEvent.StreamFailed.class,
                failed -> {
                    assertThat(failed.incomplete()).isTrue();
                    assertThat(failed.cause().errorType())
                            .isEqualTo(ModelErrorType.STREAM_INTERRUPTED);
                    assertThat(failed.cause().failurePhase())
                            .isEqualTo(FailurePhase.UPSTREAM_STREAM);
                    assertThat(failed.cause().terminationReason())
                            .isEqualTo(TerminationReason.STREAM_INTERRUPTED_AFTER_COMMIT);
                    assertThat(failed.cause().attemptedExecution().deploymentId())
                            .isEqualTo(STREAM_A);
                });
        assertThat(fake.invocations())
                .extracting(ProgrammableFakeProvider.Invocation::deploymentId)
                .containsExactly(STREAM_A);
        assertThat(breakers.snapshot(target(STREAM_A, PROVIDER_A), Capability.STREAM_CHAT)
                        .failedCalls())
                .isEqualTo(1);
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.STREAM_FAILED);
            assertThat(diagnostic.committed()).isTrue();
            assertThat(diagnostic.attempts()).singleElement().satisfies(attempt -> {
                assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.STREAM_FAILED);
                assertThat(attempt.target().deploymentId()).isEqualTo(STREAM_A);
                assertThat(attempt.downstreamCommitMs()).isNotNull();
            });
        });
    }

    @Test
    void str33_提交后畸形Provider事件不切候选且按协议失败结算() {
        assertPostCommitStreamFailed(
                Flux.just(new ProviderChatEvent.TextDelta(null)),
                ModelErrorType.PROTOCOL_CONTRACT,
                FailurePhase.PROVIDER_RESPONSE_VALIDATION);
    }

    @Test
    void str19_提交后合法ProviderError不切候选且只发一个StreamFailed() {
        assertPostCommitStreamFailed(
                Flux.error(ProviderFailureException.of(AttemptFailureType.UPSTREAM_ERROR_EVENT)),
                ModelErrorType.SERVER_ERROR,
                FailurePhase.UPSTREAM_STREAM);
    }

    /**
     * Commits the preferred candidate with one text delta, then drives {@code afterCommitBehavior}
     * and asserts the call stays locked on that deployment and ends with exactly one
     * {@code StreamFailed} carrying {@code errorType + failurePhase}.
     */
    private static void assertPostCommitStreamFailed(
            Flux<ProviderChatEvent> afterCommitBehavior,
            ModelErrorType errorType,
            FailurePhase failurePhase) {
        var catalog = catalog();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.concat(
                        Flux.just(new ProviderChatEvent.TextDelta("部分")), afterCommitBehavior)));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        StreamingChatClient client = new RoutingStreamingChatClient(
                catalog, Map.of(PROVIDER_A, fake, PROVIDER_B, fake), breakers, diagnostics);

        List<ChatStreamEvent> events = client.stream(command()).collectList().block();

        assertThat(events).hasSize(3);
        assertThat(events).filteredOn(ChatStreamEvent.StreamFailed.class::isInstance).hasSize(1);
        assertThat(events.get(2)).isInstanceOfSatisfying(
                ChatStreamEvent.StreamFailed.class,
                failed -> {
                    assertThat(failed.cause().errorType()).isEqualTo(errorType);
                    assertThat(failed.cause().failurePhase()).isEqualTo(failurePhase);
                    assertThat(failed.cause().terminationReason())
                            .isEqualTo(TerminationReason.STREAM_INTERRUPTED_AFTER_COMMIT);
                });
        assertThat(fake.invocations()).hasSize(1);
        assertThat(breakers.snapshot(target(STREAM_A, PROVIDER_A), Capability.STREAM_CHAT)
                        .failedCalls())
                .isEqualTo(1);
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.STREAM_FAILED);
            assertThat(diagnostic.committed()).isTrue();
            assertThat(diagnostic.attempts()).hasSize(1);
        });
    }

    @Test
    void str28_提交前候选耗尽走onError且零事件不发StreamFailed() {
        var catalog = catalog();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.error(
                        ProviderFailureException.http(503, null, "req_****x"))));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        StreamingChatClient client = new RoutingStreamingChatClient(
                catalog, Map.of(PROVIDER_A, fake, PROVIDER_B, fake), breakers, diagnostics);
        var events = new ArrayList<ChatStreamEvent>();
        var failure = new AtomicReference<Throwable>();

        client.stream(command()).subscribe(events::add, failure::set);

        assertThat(events).isEmpty();
        assertThat(failure.get()).isInstanceOfSatisfying(ModelCallException.class, modelFailure -> {
            assertThat(modelFailure.terminalCause().errorType())
                    .isEqualTo(ModelErrorType.SERVER_ERROR);
            assertThat(modelFailure.terminalCause().terminationReason())
                    .isEqualTo(TerminationReason.ALL_CANDIDATES_EXHAUSTED);
            assertThat(modelFailure.terminalCause().attemptedExecution().deploymentId())
                    .isEqualTo(STREAM_B);
            assertThat(modelFailure.execution().attemptCount()).isEqualTo(2);
            assertThat(modelFailure.execution().failoverCount()).isEqualTo(1);
        });
        assertThat(fake.invocations())
                .extracting(ProgrammableFakeProvider.Invocation::deploymentId)
                .containsExactly(STREAM_A, STREAM_B);
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.FAILED);
            assertThat(diagnostic.committed()).isFalse();
            assertThat(diagnostic.terminationReason())
                    .isEqualTo(TerminationReason.ALL_CANDIDATES_EXHAUSTED);
            assertThat(diagnostic.attempts()).hasSize(2);
            assertThat(diagnostic.attempts())
                    .allSatisfy(attempt -> assertThat(attempt.downstreamCommitMs()).isNull());
            assertThat(diagnostic.routeEvents()).extracting(event -> event.eventType())
                    .containsExactly(
                            RouteEventType.ROUTE_COMPLETED,
                            RouteEventType.FAILOVER_SELECTED,
                            RouteEventType.CALL_TERMINATED);
        });
    }

    @Test
    void str31_首事件前上游断开按首事件阶段计NETWORK并切换候选() {
        assertPreCommitFailoverPhase(
                Flux.empty(), ModelErrorType.NETWORK, FailurePhase.FIRST_UPSTREAM_EVENT);
    }

    @Test
    void str32_首有效块前上游断开按首有效块阶段计NETWORK且元数据不下发() {
        assertPreCommitFailoverPhase(
                Flux.just(new ProviderChatEvent.ProbeSignal(4)),
                ModelErrorType.NETWORK,
                FailurePhase.FIRST_MEANINGFUL_CHUNK);
    }

    @Test
    void str34_提交前合法ProviderError按UpstreamStream计breaker并切换候选() {
        assertPreCommitFailoverPhase(
                Flux.error(ProviderFailureException.of(AttemptFailureType.UPSTREAM_ERROR_EVENT)),
                ModelErrorType.SERVER_ERROR,
                FailurePhase.UPSTREAM_STREAM);
    }

    @Test
    void str08_提交前畸形事件按协议校验阶段计breaker并切换候选() {
        assertPreCommitFailoverPhase(
                Flux.just(new ProviderChatEvent.TextDelta(null)),
                ModelErrorType.PROTOCOL_CONTRACT,
                FailurePhase.PROVIDER_RESPONSE_VALIDATION);
    }

    /**
     * Drives the preferred candidate with {@code preferredBehavior} and asserts the failure is
     * classified as {@code errorType + failurePhase}, settled on its breaker, and recovered by
     * switching to the next candidate without leaking any preferred-candidate event downstream.
     */
    private static void assertPreCommitFailoverPhase(
            Flux<ProviderChatEvent> preferredBehavior,
            ModelErrorType errorType,
            FailurePhase failurePhase) {
        var catalog = catalog();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> deployment.id().equals(STREAM_A)
                        ? preferredBehavior
                        : Flux.just(
                                new ProviderChatEvent.TextDelta("次选"),
                                new ProviderChatEvent.Completed(ChatFinishReason.STOP, null))));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        StreamingChatClient client = new RoutingStreamingChatClient(
                catalog, Map.of(PROVIDER_A, fake, PROVIDER_B, fake), breakers, diagnostics);

        List<ChatStreamEvent> events = client.stream(command()).collectList().block();

        assertThat(events).hasSize(3);
        assertThat(events.get(0)).isInstanceOfSatisfying(
                ChatStreamEvent.StreamStarted.class,
                started -> assertThat(started.execution().actualExecution().deploymentId())
                        .isEqualTo(STREAM_B));
        assertThat(events.get(1)).isEqualTo(new ChatStreamEvent.TextDelta("次选"));
        assertThat(breakers.snapshot(target(STREAM_A, PROVIDER_A), Capability.STREAM_CHAT)
                        .failedCalls())
                .isEqualTo(1);
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.SUCCEEDED);
            assertThat(diagnostic.attempts()).first().satisfies(attempt -> {
                assertThat(attempt.target().deploymentId()).isEqualTo(STREAM_A);
                assertThat(attempt.normalizedErrorType()).isEqualTo(errorType);
                assertThat(attempt.failurePhase()).isEqualTo(failurePhase);
            });
            assertThat(diagnostic.routeEvents()).extracting(event -> event.eventType())
                    .containsExactly(
                            RouteEventType.ROUTE_COMPLETED,
                            RouteEventType.FAILOVER_SELECTED,
                            RouteEventType.CALL_TERMINATED);
        });
    }

    @Test
    void 首选halfOpen许可被占满时按breaker决策切换候选并写有序routeEvent() {
        var catalog = catalog();
        var clock = new MutableClock(Instant.parse("2026-08-04T00:00:00Z"));
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), clock, transition -> true);
        ExecutionTarget targetA = target(STREAM_A, PROVIDER_A);
        for (int call = 0; call < catalog.runtimePolicy().breaker().minimumNumberOfCalls(); call++) {
            ((BreakerPermission) breakers.tryAcquire(targetA, Capability.STREAM_CHAT))
                    .fail(Duration.ofMillis(10), AttemptFailureType.SERVER_ERROR_RESPONSE);
        }
        clock.advance(catalog.runtimePolicy().breaker().openWaitDuration());
        BreakerPermission firstHeld =
                (BreakerPermission) breakers.tryAcquire(targetA, Capability.STREAM_CHAT);
        BreakerPermission secondHeld =
                (BreakerPermission) breakers.tryAcquire(targetA, Capability.STREAM_CHAT);
        assertThat(firstHeld.state()).isEqualTo(BreakerState.HALF_OPEN);

        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.just(
                        new ProviderChatEvent.TextDelta("次选"),
                        new ProviderChatEvent.Completed(ChatFinishReason.STOP, null))));
        var diagnostics = new CapturingDiagnosticRecorder();
        StreamingChatClient client = new RoutingStreamingChatClient(
                catalog, Map.of(PROVIDER_A, fake, PROVIDER_B, fake), breakers, diagnostics);

        try {
            List<ChatStreamEvent> events = client.stream(command()).collectList().block();

            assertThat(events.get(0)).isInstanceOfSatisfying(
                    ChatStreamEvent.StreamStarted.class,
                    started -> {
                        assertThat(started.execution().actualExecution().deploymentId())
                                .isEqualTo(STREAM_B);
                        assertThat(started.execution().attemptCount()).isEqualTo(1);
                        assertThat(started.execution().failoverCount()).isEqualTo(1);
                    });
            assertThat(fake.invocations())
                    .extracting(ProgrammableFakeProvider.Invocation::deploymentId)
                    .containsExactly(STREAM_B);
            assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
                assertThat(diagnostic.attempts()).singleElement().satisfies(attempt -> {
                    assertThat(attempt.attemptKind()).isEqualTo(AttemptKind.FAILOVER);
                    assertThat(attempt.target().deploymentId()).isEqualTo(STREAM_B);
                });
                assertThat(diagnostic.routeEvents()).extracting(event -> event.eventType())
                        .containsExactly(
                                RouteEventType.ROUTE_COMPLETED,
                                RouteEventType.BREAKER_PERMISSION_REJECTED,
                                RouteEventType.FAILOVER_SELECTED,
                                RouteEventType.CALL_TERMINATED);
                assertThat(diagnostic.routeEvents().get(1)).satisfies(event -> {
                    assertThat(event.deploymentId()).isEqualTo(STREAM_A);
                    assertThat(event.reason()).isEqualTo("HALF_OPEN");
                });
                assertThat(diagnostic.routeEvents()).extracting(event -> event.sequenceNo())
                        .isSorted();
            });
        } finally {
            firstHeld.release();
            secondHeld.release();
        }
    }

    @Test
    void str22_提交前取消释放许可且不记为breaker失败() {
        var catalog = catalog();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.never()));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        StreamingChatClient client = new RoutingStreamingChatClient(
                catalog, Map.of(PROVIDER_A, fake, PROVIDER_B, fake), breakers, diagnostics);
        var events = new ArrayList<ChatStreamEvent>();

        var subscription = client.stream(command()).subscribe(events::add);
        subscription.dispose();

        assertThat(events).isEmpty();
        assertThat(fake.cancelledCount()).isEqualTo(1);
        assertThat(fake.releasedCount()).isEqualTo(1);
        assertThat(breakers.snapshot(target(STREAM_A, PROVIDER_A), Capability.STREAM_CHAT)
                        .bufferedCalls())
                .isZero();
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.CANCELED);
            assertThat(diagnostic.committed()).isFalse();
        });
    }

    @Test
    void str23_提交后取消不伪造失败事件且只结算一次许可() {
        var catalog = catalog();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.concat(
                        Flux.just(new ProviderChatEvent.TextDelta("部分")), Flux.never())));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        StreamingChatClient client = new RoutingStreamingChatClient(
                catalog, Map.of(PROVIDER_A, fake, PROVIDER_B, fake), breakers, diagnostics);
        var events = new ArrayList<ChatStreamEvent>();

        var subscription = client.stream(command()).subscribe(events::add);
        subscription.dispose();

        assertThat(events).hasSize(2);
        assertThat(events).noneMatch(ChatStreamEvent.StreamFailed.class::isInstance);
        assertThat(fake.releasedCount()).isEqualTo(1);
        assertThat(breakers.snapshot(target(STREAM_A, PROVIDER_A), Capability.STREAM_CHAT)
                        .bufferedCalls())
                .isZero();
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.CANCELED);
            assertThat(diagnostic.committed()).isTrue();
            assertThat(diagnostic.attempts()).singleElement().satisfies(attempt ->
                    assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.CANCELED));
        });
    }

    @Test
    void str12_首事件超时在提交前切换候选并保留超时阶段() {
        var catalog = catalog();
        var scheduler = VirtualTimeScheduler.create();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> deployment.id().equals(STREAM_A)
                        ? Flux.never()
                        : Flux.just(
                                new ProviderChatEvent.TextDelta("次选"),
                                new ProviderChatEvent.Completed(ChatFinishReason.STOP, null))));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        var client = new RoutingStreamingChatClient(
                catalog,
                Map.of(PROVIDER_A, fake, PROVIDER_B, fake),
                breakers,
                diagnostics,
                new RoutePlanner(),
                StreamPhaseTimeouts.defaults(),
                scheduler,
                Duration.ofMillis(1));
        var events = new ArrayList<ChatStreamEvent>();

        client.stream(command()).subscribe(events::add);
        scheduler.advanceTimeBy(Duration.ofSeconds(15));

        assertThat(events).hasSize(3);
        assertThat(events.get(0)).isInstanceOfSatisfying(
                ChatStreamEvent.StreamStarted.class,
                started -> assertThat(started.execution().actualExecution().deploymentId())
                        .isEqualTo(STREAM_B));
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.attempts()).first().satisfies(attempt -> {
                    assertThat(attempt.normalizedErrorType()).isEqualTo(ModelErrorType.TIMEOUT);
                    assertThat(attempt.failurePhase())
                            .isEqualTo(FailurePhase.FIRST_UPSTREAM_EVENT);
                }));
    }

    @Test
    void 失败切换后的诊断按真实时间顺序还原候选尝试与首包时刻() {
        var catalog = catalog();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> deployment.id().equals(STREAM_A)
                        ? Flux.error(ProviderFailureException.http(503, null, "req_****a"))
                        : Flux.just(
                                new ProviderChatEvent.ProbeSignal(4),
                                new ProviderChatEvent.TextDelta("次选"),
                                new ProviderChatEvent.Completed(ChatFinishReason.STOP, null))));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        StreamingChatClient client = new RoutingStreamingChatClient(
                catalog, Map.of(PROVIDER_A, fake, PROVIDER_B, fake), breakers, diagnostics);

        client.stream(command()).blockLast();

        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.candidates())
                    .extracting(candidate -> candidate.target().deploymentId())
                    .containsExactly(STREAM_A, STREAM_B);
            assertThat(diagnostic.routeEvents()).extracting(event -> event.sequenceNo()).isSorted();
            assertThat(diagnostic.routeEvents()).extracting(event -> event.occurredOffsetMs())
                    .isSorted();
            assertThat(diagnostic.attempts()).extracting(attempt -> attempt.attemptNo())
                    .containsExactly(1, 2);
            assertThat(diagnostic.attempts())
                    .allSatisfy(attempt -> assertThat(attempt.completedSequenceNo())
                            .isGreaterThan(attempt.startedSequenceNo()));
            assertThat(diagnostic.attempts().get(1)).satisfies(attempt -> {
                assertThat(attempt.firstUpstreamEventMs()).isNotNull();
                assertThat(attempt.firstMeaningfulChunkMs())
                        .isGreaterThanOrEqualTo(attempt.firstUpstreamEventMs());
                assertThat(attempt.downstreamCommitMs())
                        .isGreaterThanOrEqualTo(attempt.firstMeaningfulChunkMs());
                assertThat(attempt.streamEventCount()).isEqualTo(3);
            });
        });
    }

    @Test
    void str35_总截止时间到期不切换候选且提交前后终态各自唯一() {
        var preScheduler = VirtualTimeScheduler.create();
        var preFake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.never()));
        var preDiagnostics = new CapturingDiagnosticRecorder();
        var preFailure = new AtomicReference<Throwable>();
        client(preFake, preDiagnostics, preScheduler)
                .stream(command(Duration.ofSeconds(4)))
                .subscribe(ignored -> { }, preFailure::set);

        preScheduler.advanceTimeBy(Duration.ofSeconds(4));

        assertThat(preFailure.get()).isInstanceOfSatisfying(
                ModelCallException.class,
                failure -> {
                    assertThat(failure.terminalCause().failurePhase())
                            .isEqualTo(FailurePhase.TOTAL_DEADLINE);
                    assertThat(failure.terminalCause().terminationReason())
                            .isEqualTo(TerminationReason.DEADLINE_EXCEEDED);
                });
        assertThat(preFake.invocations()).hasSize(1);

        var postScheduler = VirtualTimeScheduler.create();
        var postFake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.concat(
                        Flux.just(new ProviderChatEvent.TextDelta("部分")), Flux.never())));
        var postDiagnostics = new CapturingDiagnosticRecorder();
        var postEvents = new ArrayList<ChatStreamEvent>();
        client(postFake, postDiagnostics, postScheduler)
                .stream(command(Duration.ofSeconds(4)))
                .subscribe(postEvents::add);

        postScheduler.advanceTimeBy(Duration.ofSeconds(4));

        assertThat(postEvents).hasSize(3);
        assertThat(postEvents.get(2)).isInstanceOfSatisfying(
                ChatStreamEvent.StreamFailed.class,
                failed -> {
                    assertThat(failed.cause().failurePhase())
                            .isEqualTo(FailurePhase.TOTAL_DEADLINE);
                    assertThat(failed.cause().terminationReason())
                            .isEqualTo(TerminationReason.STREAM_INTERRUPTED_AFTER_COMMIT);
                });
        assertThat(postFake.invocations()).hasSize(1);
        assertThat(postDiagnostics.calls).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.STREAM_FAILED));
    }

    @Test
    void str27与30_路由流同样只允许一次订阅且不重复计划逻辑调用() {
        var catalog = catalog();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.just(
                        new ProviderChatEvent.TextDelta("你好"),
                        new ProviderChatEvent.Completed(ChatFinishReason.STOP, null))));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        StreamingChatClient client = new RoutingStreamingChatClient(
                catalog, Map.of(PROVIDER_A, fake, PROVIDER_B, fake), breakers, diagnostics);
        Flux<ChatStreamEvent> stream = client.stream(command());

        assertThat(stream.collectList().block()).hasSize(3);
        var secondFailure = new AtomicReference<Throwable>();
        stream.subscribe(ignored -> { }, secondFailure::set);

        assertThat(secondFailure.get())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("stream publisher allows only one subscription");
        assertThat(fake.invocations()).hasSize(1);
        assertThat(diagnostics.calls).hasSize(1);
    }

    private static StreamingChatClient client(
            ProgrammableFakeProvider fake,
            DiagnosticRecorder diagnostics,
            VirtualTimeScheduler scheduler) {
        var catalog = catalog();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        return new RoutingStreamingChatClient(
                catalog,
                Map.of(PROVIDER_A, fake, PROVIDER_B, fake),
                breakers,
                diagnostics,
                new RoutePlanner(),
                StreamPhaseTimeouts.defaults(),
                scheduler,
                Duration.ofMillis(1));
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
        var providers = new LinkedHashMap<ProviderId, ModelProvider>();
        providers.put(PROVIDER_A, provider(PROVIDER_A, "quota-a"));
        providers.put(PROVIDER_B, provider(PROVIDER_B, "quota-b"));
        var deployments = new LinkedHashMap<DeploymentId, ModelDeployment>();
        deployments.put(STREAM_A, deployment(STREAM_A, PROVIDER_A, true, 10));
        deployments.put(STREAM_B, deployment(STREAM_B, PROVIDER_B, false, 20));
        return new ModelCatalogSnapshot(
                "test-v1", "a".repeat(64), providers, deployments, runtimePolicy());
    }

    private static ModelProvider provider(ProviderId id, String quota) {
        return new ModelProvider(
                id,
                "openai-compatible",
                id.value(),
                URI.create("http://127.0.0.1:1/v1"),
                "TEST_API_KEY",
                new DataRegion("CN_TEST"),
                new QuotaDomain(quota),
                ProviderConfigurationStatus.CONFIGURED);
    }

    private static ModelDeployment deployment(
            DeploymentId id, ProviderId providerId, boolean defaultCandidate, int priority) {
        return new ModelDeployment(
                id,
                providerId,
                id.value() + "-model",
                new ModelIdentity(id.value() + "-v1"),
                Set.of(Capability.STREAM_CHAT),
                Set.of(ServiceTier.BALANCED),
                defaultCandidate,
                priority,
                true,
                null);
    }

    private static ExecutionTarget target(DeploymentId deploymentId, ProviderId providerId) {
        return new ExecutionTarget(
                providerId, deploymentId, new ModelIdentity(deploymentId.value() + "-v1"));
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

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            instant = new AtomicReference<>(initial);
        }

        void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return zone.equals(ZoneOffset.UTC) ? this : Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
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
