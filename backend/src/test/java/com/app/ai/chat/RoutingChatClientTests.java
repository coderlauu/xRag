package com.app.ai.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.app.ai.breaker.Resilience4jModelBreakerRegistry;
import com.app.ai.breaker.BreakerPermission;
import com.app.ai.diagnostic.AttemptKind;
import com.app.ai.diagnostic.AttemptOutcome;
import com.app.ai.diagnostic.BreakerState;
import com.app.ai.diagnostic.CallOutcome;
import com.app.ai.diagnostic.CompletedCallDiagnostic;
import com.app.ai.diagnostic.DiagnosticRecorder;
import com.app.ai.diagnostic.RouteEventType;
import com.app.ai.model.AiRuntimePolicy;
import com.app.ai.model.AssistantMessage;
import com.app.ai.model.CallOptions;
import com.app.ai.model.CancellationRegistration;
import com.app.ai.model.CancellationToken;
import com.app.ai.model.Capability;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.DataRegion;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ModelCallException;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.FailurePhase;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderConfigurationStatus;
import com.app.ai.model.ProviderId;
import com.app.ai.model.QuotaDomain;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.TokenUsage;
import com.app.ai.model.TerminationReason;
import com.app.ai.model.UserMessage;
import com.app.ai.provider.ProgrammableFakeProvider;
import com.app.ai.provider.AttemptFailureType;
import com.app.ai.provider.ChatProvider;
import com.app.ai.provider.ProviderChatResult;
import com.app.ai.provider.ProviderFailureException;
import com.app.ai.routing.RoutePlanner;
import com.app.ai.runtime.NanoTicker;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RoutingChatClientTests {

    private static final ProviderId PROVIDER_A = new ProviderId("provider-a");
    private static final ProviderId PROVIDER_B = new ProviderId("provider-b");
    private static final DeploymentId CHAT_A = new DeploymentId("chat-a");
    private static final DeploymentId CHAT_B = new DeploymentId("chat-b");

    @Test
    void syn02_首选503后切到次选成功且保留中间失败attempt() {
        ModelCatalogSnapshot catalog = catalog(3, 1);
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    if (deployment.id().equals(CHAT_A)) {
                        throw ProviderFailureException.http(503, null, "req_****a");
                    }
                    return success("req_****b");
                }));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        var client = new RoutingChatClient(
                catalog,
                Map.of(PROVIDER_A, fake, PROVIDER_B, fake),
                breakers,
                diagnostics);

        var result = client.complete(command());

        assertThat(result.message()).isEqualTo(new AssistantMessage("完成", null, List.of()));
        assertThat(result.execution().actualExecution().deploymentId()).isEqualTo(CHAT_B);
        assertThat(result.execution().attemptCount()).isEqualTo(2);
        assertThat(result.execution().retryCount()).isZero();
        assertThat(result.execution().failoverCount()).isEqualTo(1);
        assertThat(fake.invocations()).extracting(ProgrammableFakeProvider.Invocation::deploymentId)
                .containsExactly(CHAT_A, CHAT_B);

        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.SUCCEEDED);
            assertThat(diagnostic.attempts()).extracting(attempt -> attempt.attemptKind())
                    .containsExactly(AttemptKind.INITIAL, AttemptKind.FAILOVER);
            assertThat(diagnostic.attempts()).extracting(attempt -> attempt.outcome())
                    .containsExactly(AttemptOutcome.FAILED, AttemptOutcome.SUCCEEDED);
            assertThat(diagnostic.routeEvents()).extracting(event -> event.eventType())
                    .containsExactly(
                            RouteEventType.ROUTE_COMPLETED,
                            RouteEventType.FAILOVER_SELECTED,
                            RouteEventType.CALL_TERMINATED);
        });
    }

    @Test
    void syn03_首选网络断开后原地等待并重试成功() {
        ModelCatalogSnapshot catalog = catalog(3, 2);
        var calls = new AtomicInteger();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    if (calls.incrementAndGet() == 1) {
                        throw ProviderFailureException.of(
                                com.app.ai.provider.AttemptFailureType.CONNECT_FAILED);
                    }
                    return success("req_****a");
                }));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        var waits = new ArrayList<Duration>();
        var client = new RoutingChatClient(
                catalog,
                Map.of(PROVIDER_A, fake, PROVIDER_B, fake),
                breakers,
                diagnostics,
                new RoutePlanner(),
                Clock.systemUTC(),
                NanoTicker.system(),
                upperBound -> Duration.ofMillis(120),
                waits::add,
                Duration.ofMillis(1));

        var result = client.complete(command());

        assertThat(result.execution().actualExecution().deploymentId()).isEqualTo(CHAT_A);
        assertThat(result.execution().attemptCount()).isEqualTo(2);
        assertThat(result.execution().retryCount()).isEqualTo(1);
        assertThat(result.execution().failoverCount()).isZero();
        assertThat(waits).containsExactly(Duration.ofMillis(120));
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.attempts()).extracting(attempt -> attempt.attemptKind())
                    .containsExactly(AttemptKind.INITIAL, AttemptKind.RETRY);
            assertThat(diagnostic.routeEvents()).extracting(event -> event.eventType())
                    .containsExactly(
                            RouteEventType.ROUTE_COMPLETED,
                            RouteEventType.RETRY_SCHEDULED,
                            RouteEventType.RETRY_WAIT_STARTED,
                            RouteEventType.CALL_TERMINATED);
        });
    }

    @Test
    void syn04_同候选达到两次上限后转下一候选() {
        ModelCatalogSnapshot catalog = catalog(3, 2);
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    if (deployment.id().equals(CHAT_A)) {
                        throw ProviderFailureException.http(503, null, "req_****a");
                    }
                    return success("req_****b");
                }));
        var diagnostics = new CapturingDiagnosticRecorder();
        var waits = new ArrayList<Duration>();
        var client = client(catalog, fake, diagnostics, waits);

        var result = client.complete(command());

        assertThat(result.execution().actualExecution().deploymentId()).isEqualTo(CHAT_B);
        assertThat(result.execution().attemptCount()).isEqualTo(3);
        assertThat(result.execution().retryCount()).isEqualTo(1);
        assertThat(result.execution().failoverCount()).isEqualTo(1);
        assertThat(fake.invocations()).extracting(ProgrammableFakeProvider.Invocation::deploymentId)
                .containsExactly(CHAT_A, CHAT_A, CHAT_B);
        assertThat(waits).containsExactly(Duration.ofMillis(120));
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.attempts()).extracting(attempt -> attempt.attemptKind())
                        .containsExactly(AttemptKind.INITIAL, AttemptKind.RETRY, AttemptKind.FAILOVER));
    }

    @Test
    void syn04_即使配置放宽也只执行一次策略允许的原地重试() {
        ModelCatalogSnapshot catalog = catalog(4, 3);
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    if (deployment.id().equals(CHAT_A)) {
                        throw ProviderFailureException.http(503, null, "req_****a");
                    }
                    return success("req_****b");
                }));
        var diagnostics = new CapturingDiagnosticRecorder();
        var client = client(catalog, fake, diagnostics, new ArrayList<>());

        var result = client.complete(command());

        assertThat(result.execution().attemptCount()).isEqualTo(3);
        assertThat(result.execution().retryCount()).isEqualTo(1);
        assertThat(result.execution().failoverCount()).isEqualTo(1);
        assertThat(fake.invocations()).extracting(ProgrammableFakeProvider.Invocation::deploymentId)
                .containsExactly(CHAT_A, CHAT_A, CHAT_B);
    }

    @ParameterizedTest
    @CsvSource({
        "400, INVALID_REQUEST",
        "401, AUTHENTICATION",
        "403, PERMISSION"
    })
    void syn05至07_不可恢复响应不重试也不切候选(int status, ModelErrorType errorType) {
        ModelCatalogSnapshot catalog = catalog(3, 2);
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    throw ProviderFailureException.http(status, null, "req_****a");
                }));
        var diagnostics = new CapturingDiagnosticRecorder();
        var waits = new ArrayList<Duration>();
        var client = client(catalog, fake, diagnostics, waits);

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client.complete(command()))
                .satisfies(failure -> {
                    assertThat(failure.terminalCause().errorType()).isEqualTo(errorType);
                    assertThat(failure.terminalCause().terminationReason())
                            .isEqualTo(TerminationReason.NON_RECOVERABLE_ERROR);
                    assertThat(failure.terminalCause().httpStatus()).isEqualTo(status);
                    assertThat(failure.execution().attemptCount()).isEqualTo(1);
                    assertThat(failure.execution().retryCount()).isZero();
                    assertThat(failure.execution().failoverCount()).isZero();
                });
        assertThat(fake.invocations()).hasSize(1);
        assertThat(waits).isEmpty();
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.attempts()).hasSize(1));
    }

    @Test
    void syn08_可接受RetryAfter按原值等待并原地重试() {
        ModelCatalogSnapshot catalog = catalog(3, 2);
        var calls = new AtomicInteger();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    if (calls.incrementAndGet() == 1) {
                        throw ProviderFailureException.http(
                                429, Duration.ofSeconds(2), "req_****a");
                    }
                    return success("req_****a");
                }));
        var diagnostics = new CapturingDiagnosticRecorder();
        var waits = new ArrayList<Duration>();
        var client = client(catalog, fake, diagnostics, waits);

        var result = client.complete(command());

        assertThat(result.execution().actualExecution().deploymentId()).isEqualTo(CHAT_A);
        assertThat(result.execution().retryCount()).isEqualTo(1);
        assertThat(result.execution().failoverCount()).isZero();
        assertThat(waits).containsExactly(Duration.ofSeconds(2));
    }

    @Test
    void syn09至10_RetryAfter过长时只切独立配额候选() {
        ModelCatalogSnapshot catalog = catalog(3, 2);
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    if (deployment.id().equals(CHAT_A)) {
                        throw ProviderFailureException.http(
                                429, Duration.ofMillis(5_001), "req_****a");
                    }
                    return success("req_****b");
                }));
        var diagnostics = new CapturingDiagnosticRecorder();
        var waits = new ArrayList<Duration>();
        var client = client(catalog, fake, diagnostics, waits);

        var result = client.complete(command());

        assertThat(result.execution().actualExecution().deploymentId()).isEqualTo(CHAT_B);
        assertThat(result.execution().retryCount()).isZero();
        assertThat(result.execution().failoverCount()).isEqualTo(1);
        assertThat(waits).isEmpty();
    }

    @Test
    void syn11与18_缺RetryAfter且只有同配额候选时直接限流失败() {
        ModelCatalogSnapshot catalog = catalog(3, 2, "quota-a");
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    throw ProviderFailureException.http(429, null, "req_****a");
                }));
        var diagnostics = new CapturingDiagnosticRecorder();
        var waits = new ArrayList<Duration>();
        var client = client(catalog, fake, diagnostics, waits);

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client.complete(command()))
                .satisfies(failure -> {
                    assertThat(failure.terminalCause().errorType())
                            .isEqualTo(ModelErrorType.RATE_LIMITED);
                    assertThat(failure.terminalCause().terminationReason())
                            .isEqualTo(TerminationReason.ALL_CANDIDATES_EXHAUSTED);
                    assertThat(failure.execution().attemptCount()).isEqualTo(1);
                    assertThat(failure.execution().retryCount()).isZero();
                    assertThat(failure.execution().failoverCount()).isZero();
                });
        assertThat(fake.invocations()).hasSize(1);
        assertThat(waits).isEmpty();
    }

    @Test
    void syn13_三次可恢复失败耗尽后保留全部attempt() {
        ModelCatalogSnapshot catalog = catalog(3, 2);
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    throw ProviderFailureException.http(503, null, "req_****x");
                }));
        var diagnostics = new CapturingDiagnosticRecorder();
        var waits = new ArrayList<Duration>();
        var client = client(catalog, fake, diagnostics, waits);

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client.complete(command()))
                .satisfies(failure -> {
                    assertThat(failure.terminalCause().errorType())
                            .isEqualTo(ModelErrorType.SERVER_ERROR);
                    assertThat(failure.terminalCause().failurePhase())
                            .isEqualTo(FailurePhase.RESPONSE_HEADERS);
                    assertThat(failure.terminalCause().terminationReason())
                            .isEqualTo(TerminationReason.ALL_CANDIDATES_EXHAUSTED);
                    assertThat(failure.execution().attemptCount()).isEqualTo(3);
                    assertThat(failure.execution().retryCount()).isEqualTo(1);
                    assertThat(failure.execution().failoverCount()).isEqualTo(1);
                });
        assertThat(fake.invocations()).extracting(ProgrammableFakeProvider.Invocation::deploymentId)
                .containsExactly(CHAT_A, CHAT_A, CHAT_B);
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.attempts()).hasSize(3);
            assertThat(diagnostic.attempts()).extracting(attempt -> attempt.attemptKind())
                    .containsExactly(AttemptKind.INITIAL, AttemptKind.RETRY, AttemptKind.FAILOVER);
        });
    }

    @Test
    void syn14_重试等待后取消不发第二次请求也不结算新attempt() {
        ModelCatalogSnapshot catalog = catalog(3, 2);
        var token = new MutableCancellationToken();
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    throw ProviderFailureException.of(
                            com.app.ai.provider.AttemptFailureType.CONNECT_FAILED);
                }));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        var client = new RoutingChatClient(
                catalog,
                Map.of(PROVIDER_A, fake, PROVIDER_B, fake),
                breakers,
                diagnostics,
                new RoutePlanner(),
                Clock.systemUTC(),
                NanoTicker.system(),
                upperBound -> Duration.ofMillis(20),
                ignored -> token.cancel(),
                Duration.ofMillis(1));

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client.complete(command(token, null)))
                .satisfies(failure -> {
                    assertThat(failure.terminalCause().errorType()).isEqualTo(ModelErrorType.CANCELED);
                    assertThat(failure.terminalCause().terminationReason())
                            .isEqualTo(TerminationReason.CALLER_CANCELED);
                    assertThat(failure.execution().attemptCount()).isEqualTo(1);
                    assertThat(failure.execution().retryCount()).isZero();
                });
        assertThat(fake.invocations()).hasSize(1);
        assertThat(breakers.snapshot(target(CHAT_A, PROVIDER_A), Capability.CHAT).bufferedCalls())
                .isEqualTo(1);
    }

    @Test
    void syn15_调用前线程已中断时attempt为零且保留中断标志() {
        ModelCatalogSnapshot catalog = catalog(3, 2);
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> success("req_****x")));
        var diagnostics = new CapturingDiagnosticRecorder();
        var client = client(catalog, fake, diagnostics, new ArrayList<>());

        try {
            Thread.currentThread().interrupt();
            assertThatExceptionOfType(ModelCallException.class)
                    .isThrownBy(() -> client.complete(command()))
                    .satisfies(failure -> {
                        assertThat(failure.terminalCause().errorType())
                                .isEqualTo(ModelErrorType.CANCELED);
                        assertThat(failure.execution().attemptCount()).isZero();
                    });
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(fake.invocations()).isEmpty();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void dia20_重试等待预算不足时按真实顺序终止为总Deadline() {
        ModelCatalogSnapshot catalog = catalog(3, 2);
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    throw ProviderFailureException.of(
                            com.app.ai.provider.AttemptFailureType.CONNECT_FAILED);
                }));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        var waits = new ArrayList<Duration>();
        var client = new RoutingChatClient(
                catalog,
                Map.of(PROVIDER_A, fake, PROVIDER_B, fake),
                breakers,
                diagnostics,
                new RoutePlanner(),
                Clock.systemUTC(),
                () -> 0,
                upperBound -> Duration.ofMillis(120),
                waits::add,
                Duration.ofMillis(1));

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client.complete(command(
                        CancellationToken.none(), Duration.ofMillis(100))))
                .satisfies(failure -> {
                    assertThat(failure.terminalCause().errorType()).isEqualTo(ModelErrorType.TIMEOUT);
                    assertThat(failure.terminalCause().failurePhase())
                            .isEqualTo(FailurePhase.TOTAL_DEADLINE);
                    assertThat(failure.terminalCause().terminationReason())
                            .isEqualTo(TerminationReason.DEADLINE_EXCEEDED);
                    assertThat(failure.execution().attemptCount()).isEqualTo(1);
                });
        assertThat(waits).isEmpty();
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.normalizedErrorType()).isEqualTo(ModelErrorType.TIMEOUT);
            assertThat(diagnostic.failurePhase()).isEqualTo(FailurePhase.TOTAL_DEADLINE);
            assertThat(diagnostic.routeEvents()).extracting(event -> event.eventType())
                    .containsExactly(
                            RouteEventType.ROUTE_COMPLETED,
                            RouteEventType.RETRY_SCHEDULED,
                            RouteEventType.RETRY_WAIT_STARTED,
                            RouteEventType.BUDGET_EXHAUSTED,
                            RouteEventType.CALL_TERMINATED);
        });
    }

    @Test
    void syn16_诊断Recorder异常不改变成功结果() {
        ModelCatalogSnapshot catalog = catalog(3, 1);
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> success("req_****a")));
        DiagnosticRecorder throwingRecorder = new DiagnosticRecorder() {
            @Override
            public boolean offer(CompletedCallDiagnostic diagnostic) {
                throw new IllegalStateException("diagnostic unavailable");
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
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        var client = new RoutingChatClient(
                catalog,
                Map.of(PROVIDER_A, fake, PROVIDER_B, fake),
                breakers,
                throwingRecorder);

        var result = client.complete(command());

        assertThat(result.message()).isEqualTo(new AssistantMessage("完成", null, List.of()));
        assertThat(result.execution().attemptCount()).isEqualTo(1);
    }

    @Test
    void syn17_Provider原始异常与Prompt不跨公开异常和诊断seam() {
        ModelCatalogSnapshot catalog = catalog(2, 1);
        String secret = "sk-secret-TOP_SECRET_PROMPT";
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    throw new IllegalStateException(secret);
                }));
        var diagnostics = new CapturingDiagnosticRecorder();
        var client = client(catalog, fake, diagnostics, new ArrayList<>());
        var command = new ChatCommand(
                List.of(new UserMessage(secret)),
                List.of(),
                ServiceTier.BALANCED,
                CallOptions.defaults());

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client.complete(command))
                .satisfies(failure -> {
                    assertThat(failure.toString()).doesNotContain(secret);
                    assertThat(failure.getCause()).isNull();
                    assertThat(failure.terminalCause().errorType())
                            .isEqualTo(ModelErrorType.PROTOCOL_CONTRACT);
                });
        assertThat(diagnostics.calls.toString()).doesNotContain(secret);
    }

    @Test
    void syn20_空调用参数归一为InvalidRequest且不尝试候选() {
        ModelCatalogSnapshot catalog = catalog(3, 2);
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> success("req_****x")));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        var client = new RoutingChatClient(
                catalog,
                Map.of(PROVIDER_A, fake, PROVIDER_B, fake),
                breakers,
                diagnostics);

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client.complete(null))
                .satisfies(failure -> {
                    assertThat(failure.terminalCause().errorType())
                            .isEqualTo(ModelErrorType.INVALID_REQUEST);
                    assertThat(failure.terminalCause().terminationReason())
                            .isEqualTo(TerminationReason.INVALID_REQUEST);
                    assertThat(failure.execution().attemptCount()).isZero();
                });
        assertThat(fake.invocations()).isEmpty();
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.normalizedErrorType()).isEqualTo(ModelErrorType.INVALID_REQUEST);
            assertThat(diagnostic.terminationReason()).isEqualTo(TerminationReason.INVALID_REQUEST);
            assertThat(diagnostic.candidates()).isEmpty();
            assertThat(diagnostic.attempts()).isEmpty();
        });
        assertThat(breakers.snapshot(target(CHAT_A, PROVIDER_A), Capability.CHAT).bufferedCalls())
                .isZero();
    }

    @Test
    void 未Dispatch的Provider决策不产生Attempt或虚假生命周期序号() {
        ModelCatalogSnapshot catalog = catalog(3, 2);
        ChatProvider provider = (deployment, request, context) -> success("req_****x");
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        var client = new RoutingChatClient(
                catalog,
                Map.of(PROVIDER_A, provider, PROVIDER_B, provider),
                breakers,
                diagnostics);

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client.complete(command()))
                .satisfies(failure -> {
                    assertThat(failure.terminalCause().errorType())
                            .isEqualTo(ModelErrorType.PROTOCOL_CONTRACT);
                    assertThat(failure.execution().attemptCount()).isZero();
                });
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.attempts()).isEmpty();
            assertThat(diagnostic.routeEvents()).extracting(event -> event.sequenceNo())
                    .containsExactly(1, 2);
        });
    }

    @Test
    void syn26_已Dispatch但返回空响应计协议失败并切候选() {
        ModelCatalogSnapshot catalog = catalog(3, 2);
        ChatProvider malformed = (deployment, request, context) -> {
            context.lifecycle().dispatched();
            return null;
        };
        ChatProvider fallback = (deployment, request, context) -> {
            context.lifecycle().dispatched();
            return success("req_****b");
        };
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        var client = new RoutingChatClient(
                catalog,
                Map.of(PROVIDER_A, malformed, PROVIDER_B, fallback),
                breakers,
                diagnostics);

        var result = client.complete(command());

        assertThat(result.execution().actualExecution().deploymentId()).isEqualTo(CHAT_B);
        assertThat(result.execution().attemptCount()).isEqualTo(2);
        assertThat(result.execution().failoverCount()).isEqualTo(1);
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.attempts()).extracting(attempt -> attempt.normalizedErrorType())
                    .containsExactly(ModelErrorType.PROTOCOL_CONTRACT, null);
            assertThat(diagnostic.attempts()).extracting(attempt -> attempt.failurePhase())
                    .containsExactly(FailurePhase.PROVIDER_RESPONSE_VALIDATION, null);
        });
    }

    @Test
    void syn28_有限响应Body失败先原地重试一次再切候选() {
        ModelCatalogSnapshot catalog = catalog(3, 2);
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    if (deployment.id().equals(CHAT_A)) {
                        throw ProviderFailureException.of(AttemptFailureType.RESPONSE_BODY_FAILED);
                    }
                    return success("req_****b");
                }));
        var diagnostics = new CapturingDiagnosticRecorder();
        var waits = new ArrayList<Duration>();
        var client = client(catalog, fake, diagnostics, waits);

        var result = client.complete(command());

        assertThat(result.execution().attemptCount()).isEqualTo(3);
        assertThat(result.execution().retryCount()).isEqualTo(1);
        assertThat(result.execution().failoverCount()).isEqualTo(1);
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.attempts()).first().satisfies(attempt -> {
                assertThat(attempt.normalizedErrorType()).isEqualTo(ModelErrorType.NETWORK);
                assertThat(attempt.failurePhase()).isEqualTo(FailurePhase.UPSTREAM_RESPONSE);
            });
            assertThat(diagnostic.attempts()).extracting(attempt -> attempt.attemptKind())
                    .containsExactly(AttemptKind.INITIAL, AttemptKind.RETRY, AttemptKind.FAILOVER);
        });
    }

    @Test
    void provider调用发生在Spring事务之外() {
        ModelCatalogSnapshot catalog = catalog(3, 1);
        var transactionActive = new AtomicBoolean(true);
        ChatProvider provider = (deployment, request, context) -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            context.lifecycle().dispatched();
            return success("req_****a");
        };
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        var client = new RoutingChatClient(
                catalog,
                Map.of(PROVIDER_A, provider, PROVIDER_B, provider),
                breakers,
                diagnostics);

        client.complete(command());

        assertThat(transactionActive).isFalse();
    }

    @Test
    void rte11与dia19_halfOpen许可被占用后次选产生唯一FailoverAttempt() {
        ModelCatalogSnapshot catalog = catalog(3, 2);
        var clock = new MutableClock(Instant.parse("2026-08-03T00:00:00Z"));
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), clock, transition -> true);
        var targetA = target(CHAT_A, PROVIDER_A);
        for (int call = 0; call < catalog.runtimePolicy().breaker().minimumNumberOfCalls(); call++) {
            ((BreakerPermission) breakers.tryAcquire(targetA, Capability.CHAT))
                    .fail(Duration.ofMillis(10), AttemptFailureType.SERVER_ERROR_RESPONSE);
        }
        assertThat(breakers.snapshot(targetA, Capability.CHAT).state()).isEqualTo(BreakerState.OPEN);
        clock.advance(catalog.runtimePolicy().breaker().openWaitDuration());
        BreakerPermission firstHeld = (BreakerPermission) breakers.tryAcquire(targetA, Capability.CHAT);
        BreakerPermission secondHeld = (BreakerPermission) breakers.tryAcquire(targetA, Capability.CHAT);
        assertThat(firstHeld.state()).isEqualTo(BreakerState.HALF_OPEN);
        assertThat(secondHeld.state()).isEqualTo(BreakerState.HALF_OPEN);

        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> success("req_****b")));
        var diagnostics = new CapturingDiagnosticRecorder();
        var client = new RoutingChatClient(
                catalog,
                Map.of(PROVIDER_A, fake, PROVIDER_B, fake),
                breakers,
                diagnostics,
                new RoutePlanner(),
                clock,
                NanoTicker.system(),
                upperBound -> Duration.ZERO,
                ignored -> { },
                Duration.ofMillis(1));

        try {
            var result = client.complete(command());

            assertThat(result.execution().actualExecution().deploymentId()).isEqualTo(CHAT_B);
            assertThat(result.execution().attemptCount()).isEqualTo(1);
            assertThat(result.execution().retryCount()).isZero();
            assertThat(result.execution().failoverCount()).isEqualTo(1);
            assertThat(fake.invocations()).extracting(ProgrammableFakeProvider.Invocation::deploymentId)
                    .containsExactly(CHAT_B);
            assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
                assertThat(diagnostic.attempts()).singleElement().satisfies(attempt -> {
                    assertThat(attempt.attemptNo()).isEqualTo(1);
                    assertThat(attempt.attemptKind()).isEqualTo(AttemptKind.FAILOVER);
                });
                assertThat(diagnostic.routeEvents()).extracting(event -> event.eventType())
                        .containsExactly(
                                RouteEventType.ROUTE_COMPLETED,
                                RouteEventType.BREAKER_PERMISSION_REJECTED,
                                RouteEventType.FAILOVER_SELECTED,
                                RouteEventType.CALL_TERMINATED);
                assertThat(diagnostic.routeEvents().get(1).reason()).isEqualTo("HALF_OPEN");
            });
        } finally {
            firstHeld.release();
            secondHeld.release();
        }
    }

    @Test
    void 初始预算不足时attempt为零且事件可解释() {
        ModelCatalogSnapshot catalog = catalog(3, 2);
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> success("req_****a")));
        var diagnostics = new CapturingDiagnosticRecorder();
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        var client = new RoutingChatClient(
                catalog,
                Map.of(PROVIDER_A, fake, PROVIDER_B, fake),
                breakers,
                diagnostics,
                new RoutePlanner(),
                Clock.systemUTC(),
                () -> 0,
                upperBound -> Duration.ZERO,
                ignored -> { },
                Duration.ofMillis(1));

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client.complete(command(
                        CancellationToken.none(), Duration.ofNanos(500_000))))
                .satisfies(failure -> {
                    assertThat(failure.terminalCause().errorType()).isEqualTo(ModelErrorType.TIMEOUT);
                    assertThat(failure.execution().attemptCount()).isZero();
                });
        assertThat(fake.invocations()).isEmpty();
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.attempts()).isEmpty();
            assertThat(diagnostic.routeEvents()).extracting(event -> event.eventType())
                    .containsExactly(
                            RouteEventType.ROUTE_COMPLETED,
                            RouteEventType.BUDGET_EXHAUSTED,
                            RouteEventType.CALL_TERMINATED);
        });
    }

    private static ChatCommand command() {
        return command(CancellationToken.none(), null);
    }

    private static ChatCommand command(CancellationToken cancellation, Duration timeout) {
        return new ChatCommand(
                List.of(new UserMessage("你好")),
                List.of(),
                ServiceTier.BALANCED,
                new CallOptions(null, timeout, cancellation, Set.of()));
    }

    private static ProviderChatResult success(String requestId) {
        return new ProviderChatResult(
                new AssistantMessage("完成", null, List.of()),
                ChatFinishReason.STOP,
                new TokenUsage(3, 2, 5),
                requestId);
    }

    private static ModelCatalogSnapshot catalog(int maxAttempts, int maxSameDeploymentAttempts) {
        return catalog(maxAttempts, maxSameDeploymentAttempts, "quota-b");
    }

    private static ModelCatalogSnapshot catalog(
            int maxAttempts, int maxSameDeploymentAttempts, String providerBQuota) {
        var providers = new LinkedHashMap<ProviderId, ModelProvider>();
        providers.put(PROVIDER_A, provider(PROVIDER_A, "quota-a"));
        providers.put(PROVIDER_B, provider(PROVIDER_B, providerBQuota));
        var deployments = new LinkedHashMap<DeploymentId, ModelDeployment>();
        deployments.put(CHAT_A, deployment(CHAT_A, PROVIDER_A, true, 10));
        deployments.put(CHAT_B, deployment(CHAT_B, PROVIDER_B, false, 20));
        return new ModelCatalogSnapshot(
                "test-v1",
                "a".repeat(64),
                providers,
                deployments,
                runtimePolicy(maxAttempts, maxSameDeploymentAttempts));
    }

    private static RoutingChatClient client(
            ModelCatalogSnapshot catalog,
            ProgrammableFakeProvider fake,
            CapturingDiagnosticRecorder diagnostics,
            List<Duration> waits) {
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        return new RoutingChatClient(
                catalog,
                Map.of(PROVIDER_A, fake, PROVIDER_B, fake),
                breakers,
                diagnostics,
                new RoutePlanner(),
                Clock.systemUTC(),
                NanoTicker.system(),
                upperBound -> Duration.ofMillis(120),
                waits::add,
                Duration.ofMillis(1));
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
                Set.of(Capability.CHAT),
                Set.of(ServiceTier.BALANCED),
                defaultCandidate,
                priority,
                true,
                null);
    }

    private static com.app.ai.model.ExecutionTarget target(
            DeploymentId deploymentId, ProviderId providerId) {
        return new com.app.ai.model.ExecutionTarget(
                providerId, deploymentId, new ModelIdentity(deploymentId.value() + "-v1"));
    }

    private static AiRuntimePolicy runtimePolicy(int maxAttempts, int maxSameDeploymentAttempts) {
        return new AiRuntimePolicy(
                new AiRuntimePolicy.CapabilityLimits(
                        Duration.ofSeconds(45), maxAttempts, maxSameDeploymentAttempts),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(120), 3, 1),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofMinutes(15), 3, 2),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(20), 3, 2),
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

    private static final class MutableCancellationToken implements CancellationToken {

        private final AtomicBoolean canceled = new AtomicBoolean();

        @Override
        public boolean isCancelled() {
            return canceled.get();
        }

        @Override
        public CancellationRegistration onCancel(Runnable listener) {
            return () -> { };
        }

        void cancel() {
            canceled.set(true);
        }
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
}
