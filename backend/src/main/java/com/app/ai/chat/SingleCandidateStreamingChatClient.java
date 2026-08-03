package com.app.ai.chat;

import com.app.ai.diagnostic.AttemptDiagnostic;
import com.app.ai.diagnostic.AttemptKind;
import com.app.ai.diagnostic.AttemptOutcome;
import com.app.ai.diagnostic.BreakerState;
import com.app.ai.diagnostic.CallDiagnosticBuilder;
import com.app.ai.diagnostic.CallOutcome;
import com.app.ai.diagnostic.DiagnosticRecorder;
import com.app.ai.diagnostic.RouteCandidateDiagnostic;
import com.app.ai.diagnostic.RouteDecision;
import com.app.ai.diagnostic.RouteEventDiagnostic;
import com.app.ai.diagnostic.RouteEventType;
import com.app.ai.diagnostic.TimingDiagnostic;
import com.app.ai.diagnostic.UsageDiagnostic;
import com.app.ai.model.CallOptions;
import com.app.ai.model.Capability;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.ChatStreamEvent;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ExecutionInfo;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.ModelCallException;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.RequestId;
import com.app.ai.model.StreamingChatClient;
import com.app.ai.model.TerminalCause;
import com.app.ai.model.TerminationReason;
import com.app.ai.model.TokenUsage;
import com.app.ai.provider.AttemptFailureType;
import com.app.ai.provider.ProviderAttemptContext;
import com.app.ai.provider.ProviderChatEvent;
import com.app.ai.provider.ProviderChatRequest;
import com.app.ai.provider.ProviderFailureException;
import com.app.ai.provider.StreamingChatProvider;
import com.app.ai.runtime.FailureAction;
import com.app.ai.runtime.FailureActionPolicy;
import com.app.ai.runtime.MonotonicDeadline;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;

public final class SingleCandidateStreamingChatClient implements StreamingChatClient {

    private static final String SECOND_SUBSCRIPTION_MESSAGE =
            "stream publisher allows only one subscription";

    private final ModelCatalogSnapshot catalog;
    private final ModelDeployment deployment;
    private final ModelProvider modelProvider;
    private final StreamingChatProvider provider;
    private final DiagnosticRecorder diagnostics;

    public SingleCandidateStreamingChatClient(
            ModelCatalogSnapshot catalog,
            DeploymentId deploymentId,
            StreamingChatProvider provider,
            DiagnosticRecorder diagnostics) {
        this.catalog = Objects.requireNonNull(catalog);
        this.deployment = Objects.requireNonNull(
                catalog.deployments().get(Objects.requireNonNull(deploymentId)),
                "deployment must exist in catalog");
        this.modelProvider = Objects.requireNonNull(
                catalog.providers().get(deployment.providerId()),
                "deployment provider must exist in catalog");
        if (!deployment.capabilities().contains(Capability.STREAM_CHAT)) {
            throw new IllegalArgumentException("deployment must support STREAM_CHAT");
        }
        this.provider = Objects.requireNonNull(provider);
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    @Override
    public Flux<ChatStreamEvent> stream(ChatCommand command) {
        Objects.requireNonNull(command);
        AtomicBoolean subscribed = new AtomicBoolean();
        return Flux.defer(() -> {
            if (!subscribed.compareAndSet(false, true)) {
                return Flux.error(new IllegalStateException(SECOND_SUBSCRIPTION_MESSAGE));
            }
            return new StreamExecution(command).run();
        });
    }

    private final class StreamExecution {

        private final ChatCommand command;
        private final UUID logicalCallId = UUID.randomUUID();
        private final RequestId requestId;
        private final ExecutionTarget target;
        private final long callStartedNanos = System.nanoTime();
        private final OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        private final CallDiagnosticBuilder diagnostic;
        private final AtomicReference<State> state = new AtomicReference<>(State.PROBING);
        private final AtomicBoolean dispatched = new AtomicBoolean();
        private final AtomicBoolean diagnosticRecorded = new AtomicBoolean();
        private final AtomicBoolean emptyStream = new AtomicBoolean();
        private final AtomicInteger probeEventCount = new AtomicInteger();
        private final AtomicLong probeByteCount = new AtomicLong();
        private final AtomicInteger streamEventCount = new AtomicInteger();
        private final AtomicLong attemptStartedOffsetMs = new AtomicLong(-1);
        private final AtomicLong firstUpstreamEventMs = new AtomicLong(-1);
        private final AtomicLong firstMeaningfulChunkMs = new AtomicLong(-1);
        private final AtomicLong downstreamCommitMs = new AtomicLong(-1);

        private StreamExecution(ChatCommand command) {
            this.command = command;
            this.requestId = requestId(command.options());
            this.target = new ExecutionTarget(
                    deployment.providerId(), deployment.id(), deployment.modelIdentity());
            this.diagnostic = diagnosticBuilder();
            this.diagnostic.addCandidate(candidate());
            this.diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    1,
                    elapsedMillis(callStartedNanos),
                    RouteEventType.ROUTE_COMPLETED,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    catalog.runtimePolicy().streamChat().totalTimeout().toMillis(),
                    1));
        }

        private Flux<ChatStreamEvent> run() {
            var deadline = MonotonicDeadline.start(
                    catalog.runtimePolicy().streamChat().totalTimeout(),
                    command.options().timeout());
            var context = new ProviderAttemptContext(
                    logicalCallId,
                    1,
                    deadline,
                    command.options().cancellation(),
                    this::onDispatched);
            Flux<ProviderChatEvent> upstream;
            try {
                upstream = Objects.requireNonNull(provider.stream(
                        deployment,
                        new ProviderChatRequest(command.messages(), command.tools()),
                        context));
            } catch (RuntimeException failure) {
                upstream = Flux.error(normalizedFailure(failure));
            }
            return upstream
                    .concatMap(this::onProviderEvent, 1)
                    .concatWith(Flux.defer(this::onUpstreamComplete))
                    .onErrorResume(this::onFailure)
                    .doOnCancel(this::onCancel);
        }

        private Flux<ChatStreamEvent> onProviderEvent(ProviderChatEvent event) {
            long eventOffsetMs = elapsedMillis(callStartedNanos);
            firstUpstreamEventMs.compareAndSet(-1, eventOffsetMs);
            streamEventCount.incrementAndGet();
            if (event == null) {
                return Flux.error(ProviderFailureException.of(
                        AttemptFailureType.PROVIDER_RESPONSE_INVALID));
            }
            if (event instanceof ProviderChatEvent.Completed completed) {
                return onCompleted(completed);
            }
            if (event instanceof ProviderChatEvent.ProbeSignal signal) {
                return onProbeSignal(signal.byteCount());
            }
            if (event instanceof ProviderChatEvent.TextDelta text) {
                if (text.text() == null) {
                    return invalidProviderEvent();
                }
                if (text.text().isEmpty()) {
                    return onProbeSignal(0);
                }
            }
            if (event instanceof ProviderChatEvent.RefusalDelta refusal) {
                if (refusal.text() == null) {
                    return invalidProviderEvent();
                }
                if (refusal.text().isEmpty()) {
                    return onProbeSignal(0);
                }
            }
            if (event instanceof ProviderChatEvent.ToolCallDelta tool && !isMeaningful(tool)) {
                return invalidProviderEvent();
            }
            if (!isMeaningful(event)) {
                return invalidProviderEvent();
            }
            ChatStreamEvent publicEvent = publicEvent(event);
            firstMeaningfulChunkMs.compareAndSet(-1, eventOffsetMs);
            if (state.compareAndSet(State.PROBING, State.COMMITTED)) {
                downstreamCommitMs.compareAndSet(-1, elapsedMillis(callStartedNanos));
                var execution = new ExecutionInfo(logicalCallId, requestId, target, 1, 0, 0);
                return Flux.just(new ChatStreamEvent.StreamStarted(execution), publicEvent);
            }
            return state.get() == State.COMMITTED ? Flux.just(publicEvent) : Flux.empty();
        }

        private Flux<ChatStreamEvent> onProbeSignal(int byteCount) {
            if (state.get() != State.PROBING) {
                return Flux.empty();
            }
            int events = probeEventCount.incrementAndGet();
            long bytes = probeByteCount.addAndGet(byteCount);
            var limits = catalog.runtimePolicy().stream();
            if (events >= limits.probeMaxEvents() || bytes >= limits.probeMaxBytes()) {
                return invalidProviderEvent();
            }
            return Flux.empty();
        }

        private Flux<ChatStreamEvent> invalidProviderEvent() {
            return Flux.error(ProviderFailureException.of(
                    AttemptFailureType.PROVIDER_RESPONSE_INVALID));
        }

        private Flux<ChatStreamEvent> onCompleted(ProviderChatEvent.Completed completed) {
            if (completed.finishReason() == null) {
                return Flux.error(ProviderFailureException.of(
                        AttemptFailureType.PROVIDER_RESPONSE_INVALID));
            }
            if (state.compareAndSet(State.COMMITTED, State.COMPLETED)) {
                recordSuccess(completed);
                return Flux.just(new ChatStreamEvent.Completed(
                        completed.finishReason(), completed.usage()));
            }
            if (state.get() == State.PROBING) {
                emptyStream.set(true);
                return Flux.error(ProviderFailureException.of(
                        AttemptFailureType.PROVIDER_RESPONSE_INVALID));
            }
            return Flux.empty();
        }

        private Flux<ChatStreamEvent> onUpstreamComplete() {
            return switch (state.get()) {
                case COMPLETED, STREAM_FAILED, FAILED, CANCELED -> Flux.empty();
                case PROBING -> Flux.error(ProviderFailureException.of(
                        AttemptFailureType.UPSTREAM_RESET_BEFORE_FIRST_EVENT));
                case COMMITTED -> Flux.error(ProviderFailureException.of(
                        AttemptFailureType.UPSTREAM_STREAM_INTERRUPTED));
            };
        }

        private Flux<ChatStreamEvent> onFailure(Throwable failure) {
            ProviderFailureException normalized = normalizedFailure(failure);
            if (state.compareAndSet(State.PROBING, State.FAILED)) {
                recordFailure(normalized, false);
                return Flux.error(publicFailure(normalized, false));
            }
            if (state.compareAndSet(State.COMMITTED, State.STREAM_FAILED)) {
                recordFailure(normalized, true);
                TerminalCause cause = terminalCause(normalized, true);
                return Flux.just(new ChatStreamEvent.StreamFailed(
                        cause, stableMessage(cause.errorType()), true));
            }
            return Flux.empty();
        }

        private void onCancel() {
            if (state.compareAndSet(State.PROBING, State.CANCELED)) {
                recordCanceled(false);
            } else if (state.compareAndSet(State.COMMITTED, State.CANCELED)) {
                recordCanceled(true);
            }
        }

        private void onDispatched() {
            if (dispatched.compareAndSet(false, true)) {
                attemptStartedOffsetMs.compareAndSet(-1, elapsedMillis(callStartedNanos));
            }
        }

        private void recordSuccess(ProviderChatEvent.Completed completed) {
            recordTerminal(
                    CallOutcome.SUCCEEDED,
                    AttemptOutcome.SUCCEEDED,
                    null,
                    completed.finishReason(),
                    completed.usage(),
                    true,
                    TerminationReason.COMPLETED);
        }

        private void recordFailure(ProviderFailureException failure, boolean committed) {
            FailureAction action = FailureActionPolicy.actionFor(failure.failureType());
            CallOutcome callOutcome = action.errorType() == ModelErrorType.CANCELED
                    ? CallOutcome.CANCELED
                    : committed ? CallOutcome.STREAM_FAILED : CallOutcome.FAILED;
            AttemptOutcome attemptOutcome = committed
                    ? AttemptOutcome.STREAM_FAILED
                    : emptyStream.get()
                            ? AttemptOutcome.EMPTY_STREAM
                            : attemptOutcome(action.errorType());
            recordTerminal(
                    callOutcome,
                    attemptOutcome,
                    failure,
                    null,
                    null,
                    committed,
                    FailureActionPolicy.terminationReason(failure.failureType(), committed));
        }

        private void recordCanceled(boolean committed) {
            recordTerminal(
                    CallOutcome.CANCELED,
                    AttemptOutcome.CANCELED,
                    ProviderFailureException.of(AttemptFailureType.CALLER_CANCELED),
                    null,
                    null,
                    committed,
                    TerminationReason.CALLER_CANCELED);
        }

        private void recordTerminal(
                CallOutcome callOutcome,
                AttemptOutcome attemptOutcome,
                ProviderFailureException failure,
                ChatFinishReason finishReason,
                TokenUsage tokenUsage,
                boolean committed,
                TerminationReason termination) {
            if (!diagnosticRecorded.compareAndSet(false, true)) {
                return;
            }
            long completedOffsetMs = elapsedMillis(callStartedNanos);
            FailureAction action = failure == null
                    ? null
                    : FailureActionPolicy.actionFor(failure.failureType());
            if (dispatched.get()) {
                long attemptStart = Math.max(0, attemptStartedOffsetMs.get());
                diagnostic.addAttempt(new AttemptDiagnostic(
                        1,
                        0,
                        null,
                        null,
                        0,
                        AttemptKind.INITIAL,
                        2,
                        3,
                        target,
                        modelProvider.quotaDomain(),
                        BreakerState.CLOSED,
                        callOutcome == CallOutcome.SUCCEEDED ? BreakerState.CLOSED : null,
                        attemptOutcome,
                        action == null ? null : action.errorType(),
                        action == null ? null : action.failurePhase(),
                        failure == null ? Integer.valueOf(200) : failure.httpStatus(),
                        failure == null ? null : failure.providerRequestIdMasked(),
                        attemptStart,
                        nullableOffset(firstUpstreamEventMs),
                        nullableOffset(firstMeaningfulChunkMs),
                        nullableOffset(downstreamCommitMs),
                        Math.max(0, completedOffsetMs - attemptStart),
                        inputTokens(tokenUsage),
                        outputTokens(tokenUsage),
                        streamEventCount.get()));
            }
            diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    dispatched.get() ? 4 : 2,
                    completedOffsetMs,
                    RouteEventType.CALL_TERMINATED,
                    0,
                    dispatched.get() ? 1 : null,
                    0,
                    deployment.providerId(),
                    deployment.id(),
                    termination.name(),
                    null,
                    null,
                    null));
            var completed = diagnostic.complete(
                    callOutcome,
                    action == null ? null : action.errorType(),
                    action == null ? null : action.failurePhase(),
                    termination,
                    failure == null ? Integer.valueOf(200) : failure.httpStatus(),
                    finishReason,
                    committed,
                    null,
                    null,
                    usage(tokenUsage),
                    new TimingDiagnostic(
                            0L,
                            nullableOffset(firstUpstreamEventMs),
                            nullableOffset(firstMeaningfulChunkMs),
                            nullableOffset(downstreamCommitMs),
                            completedOffsetMs,
                            startedAt,
                            OffsetDateTime.now(ZoneOffset.UTC)));
            recordBestEffort(completed);
        }

        private CallDiagnosticBuilder diagnosticBuilder() {
            return new CallDiagnosticBuilder(
                    logicalCallId,
                    requestId.value(),
                    Capability.STREAM_CHAT,
                    command.tier(),
                    null,
                    true,
                    catalog.policyVersion(),
                    catalog.configFingerprint());
        }

        private RouteCandidateDiagnostic candidate() {
            return new RouteCandidateDiagnostic(
                    0,
                    target,
                    Capability.STREAM_CHAT,
                    command.tier(),
                    modelProvider.quotaDomain(),
                    RouteDecision.ELIGIBLE,
                    null,
                    0,
                    deployment.defaultCandidate(),
                    deployment.priority(),
                    BreakerState.CLOSED);
        }

        private ModelCallException publicFailure(
                ProviderFailureException failure, boolean committed) {
            FailureAction action = FailureActionPolicy.actionFor(failure.failureType());
            var execution = new ExecutionInfo(
                    logicalCallId, requestId, null, dispatched.get() ? 1 : 0, 0, 0);
            TerminalCause cause = terminalCause(failure, committed);
            return new ModelCallException(
                    stableMessage(action.errorType()),
                    execution,
                    cause,
                    retryableByCaller(action.errorType()));
        }

        private TerminalCause terminalCause(
                ProviderFailureException failure, boolean committed) {
            FailureAction action = FailureActionPolicy.actionFor(failure.failureType());
            TerminationReason termination = FailureActionPolicy.terminationReason(
                    failure.failureType(), committed);
            return new TerminalCause(
                    action.errorType(),
                    action.failurePhase(),
                    termination,
                    failure.httpStatus(),
                    dispatched.get() ? target : null);
        }
    }

    private static boolean isMeaningful(ProviderChatEvent event) {
        if (event instanceof ProviderChatEvent.TextDelta text) {
            return text.text() != null && !text.text().isEmpty();
        }
        if (event instanceof ProviderChatEvent.RefusalDelta refusal) {
            return refusal.text() != null && !refusal.text().isEmpty();
        }
        if (event instanceof ProviderChatEvent.ToolCallDelta tool) {
            return tool.callId() != null && !tool.callId().isBlank()
                    && tool.index() >= 0
                    && tool.name() != null && !tool.name().isBlank()
                    && tool.argumentsFragment() != null;
        }
        return false;
    }

    private static ChatStreamEvent publicEvent(ProviderChatEvent event) {
        if (event instanceof ProviderChatEvent.TextDelta text) {
            return new ChatStreamEvent.TextDelta(text.text());
        }
        if (event instanceof ProviderChatEvent.RefusalDelta refusal) {
            return new ChatStreamEvent.RefusalDelta(refusal.text());
        }
        if (event instanceof ProviderChatEvent.ToolCallDelta tool) {
            return new ChatStreamEvent.ToolCallDelta(
                    tool.callId(), tool.index(), tool.name(), tool.argumentsFragment());
        }
        throw new IllegalArgumentException("event must be a meaningful business event");
    }

    private static ProviderFailureException normalizedFailure(Throwable failure) {
        return failure instanceof ProviderFailureException providerFailure
                ? providerFailure
                : ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
    }

    private void recordBestEffort(
            com.app.ai.diagnostic.CompletedCallDiagnostic completed) {
        try {
            diagnostics.offer(completed);
        } catch (RuntimeException ignored) {
            // Diagnostics are best effort and must never change the stream result.
        }
    }

    private static Long nullableOffset(AtomicLong offset) {
        long value = offset.get();
        return value < 0 ? null : value;
    }

    private static UsageDiagnostic usage(TokenUsage usage) {
        return new UsageDiagnostic(
                inputTokens(usage), outputTokens(usage), null, null, null, null, null, null);
    }

    private static Integer inputTokens(TokenUsage usage) {
        return usage == null ? null : diagnosticTokenCount(usage.inputTokens());
    }

    private static Integer outputTokens(TokenUsage usage) {
        return usage == null ? null : diagnosticTokenCount(usage.outputTokens());
    }

    private static Integer diagnosticTokenCount(long count) {
        return count <= Integer.MAX_VALUE ? (int) count : null;
    }

    private static AttemptOutcome attemptOutcome(ModelErrorType errorType) {
        return switch (errorType) {
            case RATE_LIMITED -> AttemptOutcome.RATE_LIMITED;
            case TIMEOUT -> AttemptOutcome.TIMED_OUT;
            case CANCELED -> AttemptOutcome.CANCELED;
            default -> AttemptOutcome.FAILED;
        };
    }

    private static RequestId requestId(CallOptions options) {
        return options.requestId() == null
                ? new RequestId("request-" + UUID.randomUUID())
                : options.requestId();
    }

    private static boolean retryableByCaller(ModelErrorType errorType) {
        return switch (errorType) {
            case RATE_LIMITED, NETWORK, SERVER_ERROR, TIMEOUT, STREAM_INTERRUPTED -> true;
            default -> false;
        };
    }

    private static String stableMessage(ModelErrorType errorType) {
        return switch (errorType) {
            case INVALID_REQUEST -> "模型调用参数无效";
            case NO_ELIGIBLE_MODEL -> "没有符合条件的模型";
            case AUTHENTICATION -> "模型供应商认证失败";
            case PERMISSION -> "模型供应商权限不足";
            case RATE_LIMITED -> "模型供应商请求受限";
            case NETWORK -> "模型供应商网络异常";
            case SERVER_ERROR -> "模型供应商服务异常";
            case TIMEOUT -> "模型调用超时";
            case PROTOCOL_CONTRACT -> "模型供应商响应不符合协议";
            case CANCELED -> "模型调用已取消";
            case STREAM_INTERRUPTED -> "模型流式响应中断";
        };
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private enum State {
        PROBING,
        COMMITTED,
        COMPLETED,
        FAILED,
        STREAM_FAILED,
        CANCELED
    }
}
