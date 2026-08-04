package com.app.ai.chat;

import com.app.ai.breaker.BreakerAdmission;
import com.app.ai.breaker.BreakerPermission;
import com.app.ai.breaker.BreakerPermissionRejected;
import com.app.ai.breaker.ModelBreakerRegistry;
import com.app.ai.diagnostic.AttemptDiagnostic;
import com.app.ai.diagnostic.AttemptKind;
import com.app.ai.diagnostic.AttemptOutcome;
import com.app.ai.diagnostic.BreakerState;
import com.app.ai.diagnostic.CallDiagnosticBuilder;
import com.app.ai.diagnostic.CallOutcome;
import com.app.ai.diagnostic.CompletedCallDiagnostic;
import com.app.ai.diagnostic.DiagnosticRecorder;
import com.app.ai.diagnostic.RouteCandidateDiagnostic;
import com.app.ai.diagnostic.RouteDecision;
import com.app.ai.diagnostic.RouteEventDiagnostic;
import com.app.ai.diagnostic.RouteEventType;
import com.app.ai.diagnostic.RouteRejectionReason;
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
import com.app.ai.model.ProviderId;
import com.app.ai.model.RequestId;
import com.app.ai.model.StreamingChatClient;
import com.app.ai.model.TerminalCause;
import com.app.ai.model.TerminationReason;
import com.app.ai.model.TokenUsage;
import com.app.ai.provider.AttemptFailureType;
import com.app.ai.provider.ProviderFailureException;
import com.app.ai.provider.StreamingChatProvider;
import com.app.ai.routing.BreakerSnapshotState;
import com.app.ai.routing.RouteCandidate;
import com.app.ai.routing.RoutePlan;
import com.app.ai.routing.RoutePlanner;
import com.app.ai.routing.RouteRequest;
import com.app.ai.runtime.AttemptAdmission;
import com.app.ai.runtime.AttemptAdmissionStatus;
import com.app.ai.runtime.AttemptBudget;
import com.app.ai.runtime.FailureAction;
import com.app.ai.runtime.FailureActionPolicy;
import com.app.ai.runtime.MonotonicDeadline;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * Streaming chat entry point that drives a {@link RoutePlan} through the single-subscription
 * stream state machine.
 *
 * <p>Candidates may be switched transparently while the call is still probing; once the first
 * meaningful business event has been committed downstream the deployment is locked and any later
 * failure can only produce the single {@code StreamFailed} terminal event.
 */
public final class RoutingStreamingChatClient implements StreamingChatClient {

    private static final String SECOND_SUBSCRIPTION_MESSAGE =
            "stream publisher allows only one subscription";

    private final ModelCatalogSnapshot catalog;
    private final Map<ProviderId, StreamingChatProvider> providers;
    private final ModelBreakerRegistry breakers;
    private final DiagnosticRecorder diagnostics;
    private final RoutePlanner planner;
    private final StreamPhaseTimeouts timeouts;
    private final Scheduler timerScheduler;
    private final Duration minimumRequestBudget;

    public RoutingStreamingChatClient(
            ModelCatalogSnapshot catalog,
            Map<ProviderId, StreamingChatProvider> providers,
            ModelBreakerRegistry breakers,
            DiagnosticRecorder diagnostics) {
        this(
                catalog,
                providers,
                breakers,
                diagnostics,
                new RoutePlanner(),
                StreamPhaseTimeouts.defaults(),
                Schedulers.parallel(),
                Duration.ofMillis(1));
    }

    RoutingStreamingChatClient(
            ModelCatalogSnapshot catalog,
            Map<ProviderId, StreamingChatProvider> providers,
            ModelBreakerRegistry breakers,
            DiagnosticRecorder diagnostics,
            RoutePlanner planner,
            StreamPhaseTimeouts timeouts,
            Scheduler timerScheduler,
            Duration minimumRequestBudget) {
        this.catalog = Objects.requireNonNull(catalog);
        this.providers = Map.copyOf(providers);
        this.breakers = Objects.requireNonNull(breakers);
        this.diagnostics = Objects.requireNonNull(diagnostics);
        this.planner = Objects.requireNonNull(planner);
        this.timeouts = Objects.requireNonNull(timeouts);
        this.timerScheduler = Objects.requireNonNull(timerScheduler);
        this.minimumRequestBudget = Objects.requireNonNull(minimumRequestBudget);
        if (minimumRequestBudget.isZero() || minimumRequestBudget.isNegative()) {
            throw new IllegalArgumentException("minimumRequestBudget must be positive");
        }
    }

    @Override
    public Flux<ChatStreamEvent> stream(ChatCommand command) {
        Objects.requireNonNull(command);
        AtomicBoolean subscribed = new AtomicBoolean();
        return Flux.defer(() -> {
            if (!subscribed.compareAndSet(false, true)) {
                return Flux.error(new IllegalStateException(SECOND_SUBSCRIPTION_MESSAGE));
            }
            return new StreamCall(command).run();
        });
    }

    private final class StreamCall {

        private final ChatCommand command;
        private final UUID logicalCallId = UUID.randomUUID();
        private final RequestId requestId;
        private final long callStartedNanos = System.nanoTime();
        private final OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        private final MonotonicDeadline deadline;
        private final AttemptBudget attemptBudget;
        private final CallDiagnosticBuilder diagnostic;
        private final RoutePlan route;

        private final AtomicReference<State> state = new AtomicReference<>(State.PROBING);
        private final AtomicInteger sequence = new AtomicInteger();
        private final AtomicInteger attemptCount = new AtomicInteger();
        private final AtomicInteger failoverCount = new AtomicInteger();
        private final AtomicLong downstreamCommitMs = new AtomicLong(-1);
        private final AtomicBoolean diagnosticRecorded = new AtomicBoolean();
        private final AtomicReference<CandidateExecution> activeExecution = new AtomicReference<>();
        private final AtomicReference<ProviderFailureException> lastFailure =
                new AtomicReference<>();
        private final AtomicReference<ExecutionTarget> lastAttempted = new AtomicReference<>();

        private StreamCall(ChatCommand command) {
            this.command = command;
            this.requestId = requestId(command.options());
            var limits = catalog.runtimePolicy().streamChat();
            this.deadline = MonotonicDeadline.start(
                    limits.totalTimeout(), command.options().timeout());
            this.attemptBudget = new AttemptBudget(
                    deadline,
                    limits.maxAttemptsPerWorkUnit(),
                    limits.maxSameDeploymentAttempts());
            this.diagnostic = new CallDiagnosticBuilder(
                    logicalCallId,
                    requestId.value(),
                    Capability.STREAM_CHAT,
                    command.tier(),
                    null,
                    true,
                    catalog.policyVersion(),
                    catalog.configFingerprint());
            this.route = planner.plan(
                    catalog,
                    RouteRequest.capability(
                            Capability.STREAM_CHAT, command.tier(), command.options(), Set.of()),
                    (deploymentId, capability) -> planningState(deploymentId, capability));
        }

        private Flux<ChatStreamEvent> run() {
            route.candidates().forEach(candidate -> diagnostic.addCandidate(candidate(candidate)));
            diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    sequence.incrementAndGet(),
                    elapsedMillis(),
                    RouteEventType.ROUTE_COMPLETED,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    deadline.remaining().toMillis(),
                    route.eligibleCandidates().size()));
            return candidateFrom(0, AttemptKind.INITIAL).doOnCancel(this::onCancel);
        }

        private Flux<ChatStreamEvent> candidateFrom(int candidateIndex, AttemptKind kind) {
            return Flux.defer(() -> {
                if (candidateIndex >= route.eligibleCandidates().size()) {
                    return Flux.error(terminateExhausted());
                }
                RouteCandidate candidate = route.eligibleCandidates().get(candidateIndex);
                ModelDeployment deployment = candidate.deployment();
                ExecutionTarget target = target(deployment);

                BreakerAdmission admission = breakers.tryAcquire(target, Capability.STREAM_CHAT);
                if (admission instanceof BreakerPermissionRejected rejected) {
                    addRouteEvent(
                            RouteEventType.BREAKER_PERMISSION_REJECTED,
                            null,
                            candidate,
                            rejected.state().name());
                    return switchToNextCandidate(
                            candidateIndex + 1, "BREAKER_PERMISSION_REJECTED");
                }

                BreakerPermission permission = (BreakerPermission) admission;
                AttemptAdmission budget = attemptBudget.tryStart(
                        0, deployment.id(), minimumRequestBudget, command.options().cancellation());
                if (budget.status() != AttemptAdmissionStatus.ADMITTED) {
                    permission.release();
                    return Flux.error(rejectedAdmission(budget.status(), candidate));
                }

                var execution = new CandidateExecution(
                        candidateIndex,
                        candidate,
                        target,
                        kind,
                        permission,
                        budget.logicalAttemptNo(),
                        sequence.incrementAndGet(),
                        new CandidateStreamAttempt(
                                deployment,
                                provider(deployment.providerId()),
                                catalog.runtimePolicy().stream(),
                                timeouts,
                                timerScheduler,
                                callStartedNanos));
                activeExecution.set(execution);
                return execution.attempt
                        .run(
                                command,
                                logicalCallId,
                                execution.attemptNo,
                                deadline,
                                command.options().cancellation(),
                                () -> onDispatched(kind))
                        .concatMap(event -> onAttemptEvent(event, execution), 1)
                        .onErrorResume(failure -> onAttemptFailure(failure, execution));
            });
        }

        private void onDispatched(AttemptKind kind) {
            attemptCount.incrementAndGet();
            if (kind == AttemptKind.FAILOVER) {
                failoverCount.incrementAndGet();
            }
        }

        private Flux<ChatStreamEvent> onAttemptEvent(
                ChatStreamEvent event, CandidateExecution execution) {
            if (event instanceof ChatStreamEvent.Completed completed) {
                if (!state.compareAndSet(State.COMMITTED, State.COMPLETED)) {
                    return Flux.empty();
                }
                recordSuccess(execution, completed);
                return Flux.just(event);
            }
            if (state.compareAndSet(State.PROBING, State.COMMITTED)) {
                downstreamCommitMs.compareAndSet(-1, elapsedMillis());
                return Flux.just(
                        new ChatStreamEvent.StreamStarted(new ExecutionInfo(
                                logicalCallId,
                                requestId,
                                execution.target,
                                attemptCount.get(),
                                0,
                                failoverCount.get())),
                        event);
            }
            return state.get() == State.COMMITTED ? Flux.just(event) : Flux.empty();
        }

        private Flux<ChatStreamEvent> onAttemptFailure(
                Throwable error, CandidateExecution execution) {
            ProviderFailureException failure = execution.attempt.normalized(error);
            if (!execution.attempt.dispatched()) {
                execution.permission.release();
                return Flux.error(terminate(failure, null));
            }
            long completedOffsetMs = elapsedMillis();
            execution.permission.fail(
                    Duration.ofMillis(Math.max(
                            0, completedOffsetMs - execution.attempt.startedOffsetMs())),
                    failure.failureType());
            FailureAction action = FailureActionPolicy.actionFor(failure.failureType());
            lastFailure.set(failure);
            lastAttempted.set(execution.target);
            recordFailedAttempt(execution, failure, action, completedOffsetMs);

            if (state.get() != State.PROBING) {
                if (!state.compareAndSet(State.COMMITTED, State.STREAM_FAILED)) {
                    return Flux.empty();
                }
                TerminalCause cause = terminalCause(failure, true, execution.target);
                recordTerminal(
                        CallOutcome.STREAM_FAILED,
                        action.errorType(),
                        action.failurePhase(),
                        cause.terminationReason(),
                        failure.httpStatus(),
                        null,
                        true,
                        null,
                        execution);
                return Flux.just(new ChatStreamEvent.StreamFailed(
                        cause, stableMessage(cause.errorType()), true));
            }

            int nextIndex = nextCandidateIndex(execution, action);
            if (nextIndex >= 0) {
                addRouteEvent(
                        RouteEventType.FAILOVER_SELECTED,
                        null,
                        route.eligibleCandidates().get(nextIndex),
                        failure.failureType().name());
                return candidateFrom(nextIndex, AttemptKind.FAILOVER);
            }
            return Flux.error(terminate(failure, execution));
        }

        private Flux<ChatStreamEvent> switchToNextCandidate(int nextIndex, String reason) {
            if (nextIndex < route.eligibleCandidates().size()) {
                addRouteEvent(
                        RouteEventType.FAILOVER_SELECTED,
                        null,
                        route.eligibleCandidates().get(nextIndex),
                        reason);
                return candidateFrom(nextIndex, AttemptKind.FAILOVER);
            }
            return Flux.error(terminateExhausted());
        }

        private int nextCandidateIndex(CandidateExecution execution, FailureAction action) {
            if (attemptCount.get() >= catalog.runtimePolicy().streamChat().maxAttemptsPerWorkUnit()) {
                return -1;
            }
            ModelProvider currentProvider = catalog.providers()
                    .get(execution.candidate.deployment().providerId());
            for (int index = execution.candidateIndex + 1;
                    index < route.eligibleCandidates().size();
                    index++) {
                ModelProvider nextProvider = catalog.providers()
                        .get(route.eligibleCandidates().get(index).deployment().providerId());
                boolean independentQuota =
                        !currentProvider.quotaDomain().equals(nextProvider.quotaDomain());
                if (action.maySwitchCandidate(false, independentQuota)) {
                    return index;
                }
            }
            return -1;
        }

        private void onCancel() {
            boolean committed;
            if (state.compareAndSet(State.PROBING, State.CANCELED)) {
                committed = false;
            } else if (state.compareAndSet(State.COMMITTED, State.CANCELED)) {
                committed = true;
            } else {
                return;
            }
            CandidateExecution execution = activeExecution.get();
            if (execution != null) {
                execution.permission.release();
                recordCanceledAttempt(execution);
            }
            recordTerminal(
                    CallOutcome.CANCELED,
                    ModelErrorType.CANCELED,
                    null,
                    TerminationReason.CALLER_CANCELED,
                    null,
                    null,
                    committed,
                    null,
                    execution);
        }

        private void recordSuccess(
                CandidateExecution execution, ChatStreamEvent.Completed completed) {
            long completedOffsetMs = elapsedMillis();
            Long firstMeaningfulChunkMs = execution.attempt.firstMeaningfulChunkMs();
            long commitDurationMs = firstMeaningfulChunkMs == null
                    ? Math.max(0, completedOffsetMs - execution.attempt.startedOffsetMs())
                    : Math.max(0, firstMeaningfulChunkMs - execution.attempt.startedOffsetMs());
            execution.permission.succeed(Duration.ofMillis(commitDurationMs));
            if (execution.attemptRecorded.compareAndSet(false, true)) {
                diagnostic.addAttempt(attemptDiagnostic(
                        execution,
                        AttemptOutcome.SUCCEEDED,
                        null,
                        null,
                        200,
                        completedOffsetMs,
                        completed.usage()));
            }
            recordTerminal(
                    CallOutcome.SUCCEEDED,
                    null,
                    null,
                    TerminationReason.COMPLETED,
                    200,
                    completed.finishReason(),
                    true,
                    completed.usage(),
                    execution);
        }

        private void recordFailedAttempt(
                CandidateExecution execution,
                ProviderFailureException failure,
                FailureAction action,
                long completedOffsetMs) {
            if (!execution.attemptRecorded.compareAndSet(false, true)) {
                return;
            }
            AttemptOutcome outcome = state.get() == State.COMMITTED
                    ? AttemptOutcome.STREAM_FAILED
                    : execution.attempt.emptyStream()
                            ? AttemptOutcome.EMPTY_STREAM
                            : attemptOutcome(action.errorType());
            diagnostic.addAttempt(attemptDiagnostic(
                    execution,
                    outcome,
                    action.errorType(),
                    action.failurePhase(),
                    failure.httpStatus(),
                    completedOffsetMs,
                    null));
        }

        private void recordCanceledAttempt(CandidateExecution execution) {
            if (!execution.attempt.dispatched()
                    || !execution.attemptRecorded.compareAndSet(false, true)) {
                return;
            }
            diagnostic.addAttempt(attemptDiagnostic(
                    execution, AttemptOutcome.CANCELED, null, null, null, elapsedMillis(), null));
        }

        private AttemptDiagnostic attemptDiagnostic(
                CandidateExecution execution,
                AttemptOutcome outcome,
                ModelErrorType errorType,
                com.app.ai.model.FailurePhase failurePhase,
                Integer httpStatus,
                long completedOffsetMs,
                TokenUsage usage) {
            long startedOffsetMs = execution.attempt.startedOffsetMs();
            ModelProvider modelProvider = catalog.providers()
                    .get(execution.candidate.deployment().providerId());
            return new AttemptDiagnostic(
                    execution.attemptNo,
                    0,
                    null,
                    null,
                    execution.candidate.plannedOrder(),
                    execution.kind,
                    execution.startedSequenceNo,
                    sequence.incrementAndGet(),
                    execution.target,
                    modelProvider.quotaDomain(),
                    execution.permission.state(),
                    breakers.snapshot(execution.target, Capability.STREAM_CHAT).state(),
                    outcome,
                    errorType,
                    failurePhase,
                    httpStatus,
                    null,
                    startedOffsetMs,
                    execution.attempt.firstUpstreamEventMs(),
                    execution.attempt.firstMeaningfulChunkMs(),
                    execution.committedHere() ? nullableOffset(downstreamCommitMs) : null,
                    Math.max(0, completedOffsetMs - startedOffsetMs),
                    inputTokens(usage),
                    outputTokens(usage),
                    execution.attempt.streamEventCount());
        }

        private ModelCallException terminate(
                ProviderFailureException failure, CandidateExecution execution) {
            FailureAction action = FailureActionPolicy.actionFor(failure.failureType());
            TerminationReason termination = FailureActionPolicy.terminationReason(
                    failure.failureType(), false);
            CallOutcome outcome = action.errorType() == ModelErrorType.CANCELED
                    ? CallOutcome.CANCELED
                    : CallOutcome.FAILED;
            state.compareAndSet(State.PROBING, State.FAILED);
            recordTerminal(
                    outcome,
                    action.errorType(),
                    action.failurePhase(),
                    termination,
                    failure.httpStatus(),
                    null,
                    false,
                    null,
                    execution);
            return new ModelCallException(
                    stableMessage(action.errorType()),
                    executionInfo(),
                    new TerminalCause(
                            action.errorType(),
                            action.failurePhase(),
                            termination,
                            failure.httpStatus(),
                            lastAttempted.get()),
                    retryableByCaller(action.errorType()));
        }

        private ModelCallException terminateExhausted() {
            ProviderFailureException failure = lastFailure.get();
            if (failure != null) {
                return terminate(failure, activeExecution.get());
            }
            state.compareAndSet(State.PROBING, State.FAILED);
            recordTerminal(
                    CallOutcome.FAILED,
                    ModelErrorType.NO_ELIGIBLE_MODEL,
                    null,
                    TerminationReason.NO_ELIGIBLE_MODEL,
                    null,
                    null,
                    false,
                    null,
                    null);
            return new ModelCallException(
                    stableMessage(ModelErrorType.NO_ELIGIBLE_MODEL),
                    executionInfo(),
                    new TerminalCause(
                            ModelErrorType.NO_ELIGIBLE_MODEL,
                            null,
                            TerminationReason.NO_ELIGIBLE_MODEL,
                            null,
                            null),
                    false);
        }

        private ModelCallException rejectedAdmission(
                AttemptAdmissionStatus status, RouteCandidate candidate) {
            if (status == AttemptAdmissionStatus.CANCELED
                    || status == AttemptAdmissionStatus.INTERRUPTED) {
                return terminate(
                        ProviderFailureException.of(AttemptFailureType.CALLER_CANCELED), null);
            }
            if (status == AttemptAdmissionStatus.BUDGET_INSUFFICIENT) {
                addRouteEvent(
                        RouteEventType.BUDGET_EXHAUSTED, null, candidate, status.name());
                return terminate(
                        ProviderFailureException.of(AttemptFailureType.TOTAL_DEADLINE_EXCEEDED),
                        null);
            }
            return terminateExhausted();
        }

        private void recordTerminal(
                CallOutcome outcome,
                ModelErrorType errorType,
                com.app.ai.model.FailurePhase failurePhase,
                TerminationReason termination,
                Integer httpStatus,
                ChatFinishReason finishReason,
                boolean committed,
                TokenUsage usage,
                CandidateExecution execution) {
            if (!diagnosticRecorded.compareAndSet(false, true)) {
                return;
            }
            long completedOffsetMs = elapsedMillis();
            diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    sequence.incrementAndGet(),
                    completedOffsetMs,
                    RouteEventType.CALL_TERMINATED,
                    0,
                    attemptCount.get() == 0 ? null : attemptCount.get(),
                    execution == null ? null : execution.candidate.plannedOrder(),
                    execution == null ? null : execution.target.providerId(),
                    execution == null ? null : execution.target.deploymentId(),
                    termination.name(),
                    null,
                    deadline.remaining().toMillis(),
                    null));
            recordBestEffort(diagnostic.complete(
                    outcome,
                    errorType,
                    failurePhase,
                    termination,
                    httpStatus,
                    finishReason,
                    committed,
                    null,
                    null,
                    usage(usage),
                    new TimingDiagnostic(
                            0L,
                            execution == null ? null : execution.attempt.firstUpstreamEventMs(),
                            execution == null ? null : execution.attempt.firstMeaningfulChunkMs(),
                            nullableOffset(downstreamCommitMs),
                            completedOffsetMs,
                            startedAt,
                            OffsetDateTime.now(ZoneOffset.UTC))));
        }

        private void addRouteEvent(
                RouteEventType eventType,
                Integer attemptNo,
                RouteCandidate candidate,
                String reason) {
            diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    sequence.incrementAndGet(),
                    elapsedMillis(),
                    eventType,
                    0,
                    attemptNo,
                    candidate.plannedOrder(),
                    candidate.deployment().providerId(),
                    candidate.deployment().id(),
                    reason,
                    null,
                    deadline.remaining().toMillis(),
                    null));
        }

        private ExecutionInfo executionInfo() {
            return new ExecutionInfo(
                    logicalCallId, requestId, null, attemptCount.get(), 0, failoverCount.get());
        }

        private TerminalCause terminalCause(
                ProviderFailureException failure, boolean committed, ExecutionTarget target) {
            FailureAction action = FailureActionPolicy.actionFor(failure.failureType());
            return new TerminalCause(
                    action.errorType(),
                    action.failurePhase(),
                    FailureActionPolicy.terminationReason(failure.failureType(), committed),
                    failure.httpStatus(),
                    target);
        }

        private RouteCandidateDiagnostic candidate(RouteCandidate candidate) {
            ModelDeployment deployment = candidate.deployment();
            ModelProvider modelProvider = catalog.providers().get(deployment.providerId());
            return new RouteCandidateDiagnostic(
                    candidate.candidateOrdinal(),
                    target(deployment),
                    Capability.STREAM_CHAT,
                    command.tier(),
                    modelProvider.quotaDomain(),
                    RouteDecision.valueOf(candidate.decision().name()),
                    candidate.rejectionReason() == null
                            ? null
                            : RouteRejectionReason.valueOf(candidate.rejectionReason().name()),
                    candidate.plannedOrder(),
                    deployment.defaultCandidate(),
                    deployment.priority(),
                    BreakerState.valueOf(candidate.breakerStateSnapshot().name()));
        }

        private long elapsedMillis() {
            return CandidateStreamAttempt.elapsedMillis(callStartedNanos);
        }
    }

    /** One admitted candidate attempt: its breaker permission, ordinals and stream machine. */
    private final class CandidateExecution {

        private final int candidateIndex;
        private final RouteCandidate candidate;
        private final ExecutionTarget target;
        private final AttemptKind kind;
        private final BreakerPermission permission;
        private final int attemptNo;
        private final int startedSequenceNo;
        private final CandidateStreamAttempt attempt;
        private final AtomicBoolean attemptRecorded = new AtomicBoolean();

        private CandidateExecution(
                int candidateIndex,
                RouteCandidate candidate,
                ExecutionTarget target,
                AttemptKind kind,
                BreakerPermission permission,
                int attemptNo,
                int startedSequenceNo,
                CandidateStreamAttempt attempt) {
            this.candidateIndex = candidateIndex;
            this.candidate = candidate;
            this.target = target;
            this.kind = kind;
            this.permission = permission;
            this.attemptNo = attemptNo;
            this.startedSequenceNo = startedSequenceNo;
            this.attempt = attempt;
        }

        private boolean committedHere() {
            return attempt.committed();
        }
    }

    private BreakerSnapshotState planningState(DeploymentId deploymentId, Capability capability) {
        ModelDeployment deployment = catalog.deployments().get(deploymentId);
        return BreakerSnapshotState.valueOf(
                breakers.snapshot(target(deployment), capability).state().name());
    }

    private StreamingChatProvider provider(ProviderId providerId) {
        return Objects.requireNonNull(
                providers.get(providerId),
                "streaming chat provider is not configured for " + providerId);
    }

    private void recordBestEffort(CompletedCallDiagnostic completed) {
        try {
            diagnostics.offer(completed);
        } catch (RuntimeException ignored) {
            // Diagnostics are best effort and must never change the stream result.
        }
    }

    private static ExecutionTarget target(ModelDeployment deployment) {
        return new ExecutionTarget(
                deployment.providerId(), deployment.id(), deployment.modelIdentity());
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

    private enum State {
        PROBING,
        COMMITTED,
        COMPLETED,
        FAILED,
        STREAM_FAILED,
        CANCELED
    }
}
