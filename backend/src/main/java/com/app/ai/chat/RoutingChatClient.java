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
import com.app.ai.model.ChatClient;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatResult;
import com.app.ai.model.ExecutionInfo;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelCallException;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderId;
import com.app.ai.model.RequestId;
import com.app.ai.model.TerminalCause;
import com.app.ai.model.TerminationReason;
import com.app.ai.model.TokenUsage;
import com.app.ai.provider.AttemptFailureType;
import com.app.ai.provider.ChatProvider;
import com.app.ai.provider.ProviderAttemptContext;
import com.app.ai.provider.ProviderChatRequest;
import com.app.ai.provider.ProviderChatResult;
import com.app.ai.provider.ProviderFailureException;
import com.app.ai.routing.BreakerSnapshotState;
import com.app.ai.routing.RouteCandidate;
import com.app.ai.routing.RoutePlan;
import com.app.ai.routing.RoutePlanner;
import com.app.ai.routing.RouteRequest;
import com.app.ai.runtime.FailureAction;
import com.app.ai.runtime.FailureActionPolicy;
import com.app.ai.runtime.AttemptAdmissionStatus;
import com.app.ai.runtime.AttemptBudget;
import com.app.ai.runtime.DeadlineWaiter;
import com.app.ai.runtime.FullJitterBackoff;
import com.app.ai.runtime.JitterSource;
import com.app.ai.runtime.MonotonicDeadline;
import com.app.ai.runtime.NanoTicker;
import com.app.ai.runtime.Sleeper;
import com.app.ai.runtime.WaitOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class RoutingChatClient implements ChatClient {

    private final ModelCatalogSnapshot catalog;
    private final Map<ProviderId, ChatProvider> providers;
    private final ModelBreakerRegistry breakers;
    private final DiagnosticRecorder diagnostics;
    private final RoutePlanner planner;
    private final Clock clock;
    private final NanoTicker ticker;
    private final JitterSource jitter;
    private final Sleeper sleeper;
    private final Duration minimumRequestBudget;

    public RoutingChatClient(
            ModelCatalogSnapshot catalog,
            Map<ProviderId, ChatProvider> providers,
            ModelBreakerRegistry breakers,
            DiagnosticRecorder diagnostics) {
        this(
                catalog,
                providers,
                breakers,
                diagnostics,
                new RoutePlanner(),
                Clock.systemUTC(),
                NanoTicker.system(),
                JitterSource.random(),
                Sleeper.threadSleep(),
                Duration.ofMillis(1));
    }

    RoutingChatClient(
            ModelCatalogSnapshot catalog,
            Map<ProviderId, ChatProvider> providers,
            ModelBreakerRegistry breakers,
            DiagnosticRecorder diagnostics,
            RoutePlanner planner,
            Clock clock,
            NanoTicker ticker,
            JitterSource jitter,
            Sleeper sleeper,
            Duration minimumRequestBudget) {
        this.catalog = Objects.requireNonNull(catalog);
        this.providers = Map.copyOf(providers);
        this.breakers = Objects.requireNonNull(breakers);
        this.diagnostics = Objects.requireNonNull(diagnostics);
        this.planner = Objects.requireNonNull(planner);
        this.clock = Objects.requireNonNull(clock);
        this.ticker = Objects.requireNonNull(ticker);
        this.jitter = Objects.requireNonNull(jitter);
        this.sleeper = Objects.requireNonNull(sleeper);
        this.minimumRequestBudget = Objects.requireNonNull(minimumRequestBudget);
        if (minimumRequestBudget.isZero() || minimumRequestBudget.isNegative()) {
            throw new IllegalArgumentException("minimumRequestBudget must be positive");
        }
    }

    @Override
    public ChatResult complete(ChatCommand command) {
        if (command == null) {
            return failInvalidRequest();
        }
        UUID logicalCallId = UUID.randomUUID();
        RequestId requestId = requestId(command.options());
        long callStartedNanos = ticker.read();
        OffsetDateTime startedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        var limits = catalog.runtimePolicy().chat();
        var deadline = MonotonicDeadline.start(
                limits.totalTimeout(), command.options().timeout(), ticker);
        var attemptBudget = new AttemptBudget(
                deadline, limits.maxAttemptsPerWorkUnit(), limits.maxSameDeploymentAttempts());
        CallDiagnosticBuilder diagnostic = diagnosticBuilder(logicalCallId, requestId, command);
        RoutePlan route = planner.plan(
                catalog,
                RouteRequest.chat(command, Set.of()),
                (deploymentId, capability) -> planningState(deploymentId, capability));
        route.candidates().forEach(candidate -> diagnostic.addCandidate(candidate(candidate, command)));
        int sequence = 1;
        diagnostic.addRouteEvent(new RouteEventDiagnostic(
                sequence,
                elapsedMillis(callStartedNanos),
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

        ProviderFailureException lastFailure = null;
        ExecutionTarget lastAttempted = null;
        int attemptCount = 0;
        int retryCount = 0;
        int failoverCount = 0;
        candidateLoop:
        for (int candidateIndex = 0;
                candidateIndex < route.eligibleCandidates().size();
                candidateIndex++) {
            RouteCandidate candidate = route.eligibleCandidates().get(candidateIndex);
            ModelDeployment deployment = candidate.deployment();
            ModelProvider modelProvider = catalog.providers().get(deployment.providerId());
            ChatProvider chatProvider = provider(deployment.providerId());
            ExecutionTarget target = target(deployment);
            int candidateAdmissionCount = 0;

            while (true) {
                BreakerAdmission breakerAdmission = breakers.tryAcquire(target, Capability.CHAT);
                if (breakerAdmission instanceof BreakerPermissionRejected rejected) {
                    diagnostic.addRouteEvent(new RouteEventDiagnostic(
                            ++sequence,
                            elapsedMillis(callStartedNanos),
                            RouteEventType.BREAKER_PERMISSION_REJECTED,
                            0,
                            null,
                            candidate.plannedOrder(),
                            deployment.providerId(),
                            deployment.id(),
                            rejected.state().name(),
                            null,
                            deadline.remaining().toMillis(),
                            null));
                    if (candidateIndex + 1 < route.eligibleCandidates().size()) {
                        diagnostic.addRouteEvent(failoverEvent(
                                ++sequence,
                                callStartedNanos,
                                route.eligibleCandidates().get(candidateIndex + 1),
                                "BREAKER_PERMISSION_REJECTED",
                                deadline.remaining().toMillis()));
                        continue candidateLoop;
                    }
                    break;
                }

                BreakerPermission permission = (BreakerPermission) breakerAdmission;
                var attemptAdmission = attemptBudget.tryStart(
                        0,
                        deployment.id(),
                        minimumRequestBudget,
                        command.options().cancellation());
                if (attemptAdmission.status() != AttemptAdmissionStatus.ADMITTED) {
                    permission.release();
                    if (attemptAdmission.status() == AttemptAdmissionStatus.CANCELED
                            || attemptAdmission.status() == AttemptAdmissionStatus.INTERRUPTED) {
                        ProviderFailureException canceled = ProviderFailureException.of(
                                AttemptFailureType.CALLER_CANCELED);
                        return fail(
                                canceled,
                                FailureActionPolicy.actionFor(canceled.failureType()),
                                logicalCallId,
                                requestId,
                                diagnostic,
                                deadline.remaining().toMillis(),
                                lastAttempted,
                                attemptCount,
                                retryCount,
                                failoverCount,
                                ++sequence,
                                callStartedNanos,
                                startedAt);
                    }
                    if (attemptAdmission.status() == AttemptAdmissionStatus.BUDGET_INSUFFICIENT) {
                        diagnostic.addRouteEvent(budgetExhaustedEvent(
                                ++sequence,
                                callStartedNanos,
                                candidate,
                                deadline.remaining().toMillis()));
                        return failDeadlineExceeded(
                                logicalCallId,
                                requestId,
                                diagnostic,
                                deadline.remaining().toMillis(),
                                lastAttempted,
                                attemptCount,
                                retryCount,
                                failoverCount,
                                ++sequence,
                                callStartedNanos,
                                startedAt);
                    }
                    break;
                }

                candidateAdmissionCount++;
                int attemptNo = attemptAdmission.logicalAttemptNo();
                AttemptKind kind;
                if (candidateAdmissionCount > 1) {
                    kind = AttemptKind.RETRY;
                } else if (candidateIndex > 0 || attemptCount > 0) {
                    kind = AttemptKind.FAILOVER;
                } else {
                    kind = AttemptKind.INITIAL;
                }
                int startedSequence = ++sequence;
                AtomicBoolean dispatched = new AtomicBoolean();
                AtomicLong attemptStartedOffsetMs = new AtomicLong(elapsedMillis(callStartedNanos));
                var context = new ProviderAttemptContext(
                        logicalCallId,
                        attemptNo,
                        deadline,
                        command.options().cancellation(),
                        () -> {
                            if (dispatched.compareAndSet(false, true)) {
                                attemptStartedOffsetMs.set(elapsedMillis(callStartedNanos));
                            }
                        });
                ProviderChatResult providerResult;
                try {
                    providerResult = chatProvider.complete(
                            deployment,
                            new ProviderChatRequest(command.messages(), command.tools()),
                            context);
                    if (!dispatched.get() || providerResult == null) {
                        throw ProviderFailureException.of(
                                AttemptFailureType.PROVIDER_RESPONSE_INVALID);
                    }
                } catch (RuntimeException providerError) {
                    ProviderFailureException failure = providerError instanceof ProviderFailureException typed
                            ? typed
                            : ProviderFailureException.of(
                                    AttemptFailureType.PROVIDER_RESPONSE_INVALID);
                    FailureAction action = FailureActionPolicy.actionFor(failure.failureType());
                    long completedOffsetMs = elapsedMillis(callStartedNanos);
                    int completedSequence = ++sequence;
                    if (!dispatched.get()) {
                        permission.release();
                        return fail(
                                failure,
                                action,
                                logicalCallId,
                                requestId,
                                diagnostic,
                                deadline.remaining().toMillis(),
                                lastAttempted,
                                attemptCount,
                                retryCount,
                                failoverCount,
                                startedSequence,
                                callStartedNanos,
                                startedAt);
                    }

                    permission.fail(
                            Duration.ofMillis(Math.max(
                                    0, completedOffsetMs - attemptStartedOffsetMs.get())),
                            failure.failureType());
                    attemptCount++;
                    if (kind == AttemptKind.RETRY) {
                        retryCount++;
                    } else if (kind == AttemptKind.FAILOVER) {
                        failoverCount++;
                    }
                    lastFailure = failure;
                    lastAttempted = target;
                    diagnostic.addAttempt(failedAttempt(
                            attemptNo,
                            candidate.plannedOrder(),
                            kind,
                            startedSequence,
                            completedSequence,
                            target,
                            modelProvider,
                            permission.state(),
                            action,
                            failure,
                            attemptStartedOffsetMs.get(),
                            completedOffsetMs));

                    boolean retryCapacity = candidateAdmissionCount
                                    < limits.maxSameDeploymentAttempts()
                            && attemptCount < limits.maxAttemptsPerWorkUnit()
                            && candidateAdmissionCount == 1;
                    Duration retryDelay = retryCapacity
                            ? retryDelay(failure)
                            : null;
                    boolean retryAllowed = retryDelay != null;
                    if (retryAllowed) {
                        diagnostic.addRouteEvent(retryEvent(
                                ++sequence,
                                callStartedNanos,
                                RouteEventType.RETRY_SCHEDULED,
                                attemptNo,
                                candidate,
                                failure.failureType().name(),
                                retryDelay,
                                deadline.remaining().toMillis()));
                        diagnostic.addRouteEvent(retryEvent(
                                ++sequence,
                                callStartedNanos,
                                RouteEventType.RETRY_WAIT_STARTED,
                                attemptNo,
                                candidate,
                                failure.failureType().name(),
                                retryDelay,
                                deadline.remaining().toMillis()));
                        WaitOutcome waitOutcome = DeadlineWaiter.waitFor(
                                retryDelay, deadline, minimumRequestBudget, sleeper);
                        if (waitOutcome == WaitOutcome.COMPLETED) {
                            continue;
                        }
                        if (waitOutcome == WaitOutcome.BUDGET_INSUFFICIENT) {
                            diagnostic.addRouteEvent(budgetExhaustedEvent(
                                    ++sequence,
                                    callStartedNanos,
                                    candidate,
                                    deadline.remaining().toMillis()));
                            return failDeadlineExceeded(
                                    logicalCallId,
                                    requestId,
                                    diagnostic,
                                    deadline.remaining().toMillis(),
                                    lastAttempted,
                                    attemptCount,
                                    retryCount,
                                    failoverCount,
                                    ++sequence,
                                    callStartedNanos,
                                    startedAt);
                        }
                        ProviderFailureException canceled = ProviderFailureException.of(
                                AttemptFailureType.CALLER_CANCELED);
                        return fail(
                                canceled,
                                FailureActionPolicy.actionFor(canceled.failureType()),
                                logicalCallId,
                                requestId,
                                diagnostic,
                                deadline.remaining().toMillis(),
                                lastAttempted,
                                attemptCount,
                                retryCount,
                                failoverCount,
                                ++sequence,
                                callStartedNanos,
                                startedAt);
                    }

                    int nextCandidateIndex = attemptCount < limits.maxAttemptsPerWorkUnit()
                            ? nextCandidateIndex(route, candidateIndex, modelProvider, action)
                            : -1;
                    if (nextCandidateIndex >= 0) {
                        RouteCandidate next = route.eligibleCandidates().get(nextCandidateIndex);
                        diagnostic.addRouteEvent(failoverEvent(
                                ++sequence,
                                callStartedNanos,
                                next,
                                failure.failureType().name(),
                                deadline.remaining().toMillis()));
                        candidateIndex = nextCandidateIndex - 1;
                        continue candidateLoop;
                    }
                    return fail(
                            failure,
                            action,
                            logicalCallId,
                            requestId,
                            diagnostic,
                            deadline.remaining().toMillis(),
                            lastAttempted,
                            attemptCount,
                            retryCount,
                            failoverCount,
                            ++sequence,
                            callStartedNanos,
                            startedAt);
                }

                long completedOffsetMs = elapsedMillis(callStartedNanos);
                int completedSequence = ++sequence;
                permission.succeed(Duration.ofMillis(Math.max(
                        0, completedOffsetMs - attemptStartedOffsetMs.get())));
                attemptCount++;
                if (kind == AttemptKind.RETRY) {
                    retryCount++;
                } else if (kind == AttemptKind.FAILOVER) {
                    failoverCount++;
                }
                diagnostic.addAttempt(successfulAttempt(
                        attemptNo,
                        candidate.plannedOrder(),
                        kind,
                        startedSequence,
                        completedSequence,
                        target,
                        modelProvider,
                        permission.state(),
                        providerResult,
                        attemptStartedOffsetMs.get(),
                        completedOffsetMs));
                diagnostic.addRouteEvent(new RouteEventDiagnostic(
                        ++sequence,
                        completedOffsetMs,
                        RouteEventType.CALL_TERMINATED,
                        0,
                        attemptNo,
                        candidate.plannedOrder(),
                        deployment.providerId(),
                        deployment.id(),
                        TerminationReason.COMPLETED.name(),
                        null,
                        deadline.remaining().toMillis(),
                        null));
                ExecutionInfo execution = new ExecutionInfo(
                        logicalCallId,
                        requestId,
                        target,
                        attemptCount,
                        retryCount,
                        failoverCount);
                CompletedCallDiagnostic completed = diagnostic.complete(
                        CallOutcome.SUCCEEDED,
                        null,
                        null,
                        TerminationReason.COMPLETED,
                        200,
                        providerResult.finishReason(),
                        false,
                        null,
                        null,
                        usage(providerResult.usage()),
                        timing(callStartedNanos, startedAt));
                recordBestEffort(completed);
                return new ChatResult(
                        providerResult.message(),
                        providerResult.finishReason(),
                        providerResult.usage(),
                        execution);
            }
        }

        FailureAction action = lastFailure == null
                ? null
                : FailureActionPolicy.actionFor(lastFailure.failureType());
        if (lastFailure == null) {
            return failBeforeAttempt(
                    ModelErrorType.NO_ELIGIBLE_MODEL,
                    TerminationReason.NO_ELIGIBLE_MODEL,
                    logicalCallId,
                    requestId,
                    diagnostic,
                    deadline.remaining().toMillis(),
                    attemptCount,
                    failoverCount,
                    ++sequence,
                    callStartedNanos,
                    startedAt);
        }
        return fail(
                lastFailure,
                action,
                logicalCallId,
                requestId,
                diagnostic,
                deadline.remaining().toMillis(),
                lastAttempted,
                attemptCount,
                retryCount,
                failoverCount,
                ++sequence,
                callStartedNanos,
                startedAt);
    }

    private ChatResult failInvalidRequest() {
        UUID logicalCallId = UUID.randomUUID();
        RequestId requestId = requestId(CallOptions.defaults());
        long callStartedNanos = ticker.read();
        OffsetDateTime startedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        var deadline = MonotonicDeadline.start(
                catalog.runtimePolicy().chat().totalTimeout(), null, ticker);
        var diagnostic = new CallDiagnosticBuilder(
                logicalCallId,
                requestId.value(),
                Capability.CHAT,
                com.app.ai.model.ServiceTier.BALANCED,
                null,
                false,
                catalog.policyVersion(),
                catalog.configFingerprint());
        return failBeforeAttempt(
                ModelErrorType.INVALID_REQUEST,
                TerminationReason.INVALID_REQUEST,
                logicalCallId,
                requestId,
                diagnostic,
                deadline.remaining().toMillis(),
                0,
                0,
                1,
                callStartedNanos,
                startedAt);
    }

    private Duration retryDelay(ProviderFailureException failure) {
        FailureAction action = FailureActionPolicy.actionFor(failure.failureType());
        if (failure.failureType() == AttemptFailureType.RATE_LIMITED_RESPONSE) {
            Duration retryAfter = failure.retryAfter();
            boolean acceptable = retryAfter != null
                    && !retryAfter.isNegative()
                    && retryAfter.compareTo(Duration.ofSeconds(5)) <= 0;
            return action.mayRetrySameCandidate(acceptable) ? retryAfter : null;
        }
        return action.mayRetrySameCandidate(false)
                ? FullJitterBackoff.delay(1, jitter)
                : null;
    }

    private int nextCandidateIndex(
            RoutePlan route,
            int currentIndex,
            ModelProvider currentProvider,
            FailureAction action) {
        for (int index = currentIndex + 1; index < route.eligibleCandidates().size(); index++) {
            RouteCandidate next = route.eligibleCandidates().get(index);
            ModelProvider nextProvider = catalog.providers().get(next.deployment().providerId());
            boolean independentQuota = !currentProvider.quotaDomain().equals(nextProvider.quotaDomain());
            if (action.maySwitchCandidate(false, independentQuota)) {
                return index;
            }
        }
        return -1;
    }

    private RouteEventDiagnostic retryEvent(
            int sequence,
            long callStartedNanos,
            RouteEventType eventType,
            int attemptNo,
            RouteCandidate candidate,
            String reason,
            Duration wait,
            long remainingBudgetMs) {
        return new RouteEventDiagnostic(
                sequence,
                elapsedMillis(callStartedNanos),
                eventType,
                0,
                attemptNo,
                candidate.plannedOrder(),
                candidate.deployment().providerId(),
                candidate.deployment().id(),
                reason,
                wait.toMillis(),
                remainingBudgetMs,
                null);
    }

    private RouteEventDiagnostic budgetExhaustedEvent(
            int sequence,
            long callStartedNanos,
            RouteCandidate candidate,
            long remainingBudgetMs) {
        return new RouteEventDiagnostic(
                sequence,
                elapsedMillis(callStartedNanos),
                RouteEventType.BUDGET_EXHAUSTED,
                0,
                null,
                candidate.plannedOrder(),
                candidate.deployment().providerId(),
                candidate.deployment().id(),
                AttemptAdmissionStatus.BUDGET_INSUFFICIENT.name(),
                null,
                remainingBudgetMs,
                null);
    }

    private ChatResult failDeadlineExceeded(
            UUID logicalCallId,
            RequestId requestId,
            CallDiagnosticBuilder diagnostic,
            long remainingBudgetMs,
            ExecutionTarget lastAttempted,
            int attemptCount,
            int retryCount,
            int failoverCount,
            int sequence,
            long callStartedNanos,
            OffsetDateTime startedAt) {
        ProviderFailureException failure = ProviderFailureException.of(
                AttemptFailureType.TOTAL_DEADLINE_EXCEEDED);
        return fail(
                failure,
                FailureActionPolicy.actionFor(failure.failureType()),
                logicalCallId,
                requestId,
                diagnostic,
                remainingBudgetMs,
                lastAttempted,
                attemptCount,
                retryCount,
                failoverCount,
                sequence,
                callStartedNanos,
                startedAt);
    }

    private ChatResult fail(
            ProviderFailureException failure,
            FailureAction action,
            UUID logicalCallId,
            RequestId requestId,
            CallDiagnosticBuilder diagnostic,
            long remainingBudgetMs,
            ExecutionTarget lastAttempted,
            int attemptCount,
            int retryCount,
            int failoverCount,
            int sequence,
            long callStartedNanos,
            OffsetDateTime startedAt) {
        TerminationReason termination = FailureActionPolicy.terminationReason(
                failure.failureType(), false);
        diagnostic.addRouteEvent(new RouteEventDiagnostic(
                sequence,
                elapsedMillis(callStartedNanos),
                RouteEventType.CALL_TERMINATED,
                0,
                attemptCount == 0 ? null : attemptCount,
                null,
                lastAttempted == null ? null : lastAttempted.providerId(),
                lastAttempted == null ? null : lastAttempted.deploymentId(),
                termination.name(),
                null,
                remainingBudgetMs,
                null));
        CallOutcome outcome = action.errorType() == ModelErrorType.CANCELED
                ? CallOutcome.CANCELED
                : CallOutcome.FAILED;
        recordBestEffort(diagnostic.complete(
                outcome,
                action.errorType(),
                action.failurePhase(),
                termination,
                failure.httpStatus(),
                null,
                false,
                null,
                null,
                usage(null),
                timing(callStartedNanos, startedAt)));
        throw new ModelCallException(
                stableMessage(action.errorType()),
                new ExecutionInfo(
                        logicalCallId, requestId, null, attemptCount, retryCount, failoverCount),
                new TerminalCause(
                        action.errorType(),
                        action.failurePhase(),
                        termination,
                        failure.httpStatus(),
                        lastAttempted),
                retryableByCaller(action.errorType()));
    }

    private ChatResult failBeforeAttempt(
            ModelErrorType errorType,
            TerminationReason termination,
            UUID logicalCallId,
            RequestId requestId,
            CallDiagnosticBuilder diagnostic,
            long remainingBudgetMs,
            int attemptCount,
            int failoverCount,
            int sequence,
            long callStartedNanos,
            OffsetDateTime startedAt) {
        diagnostic.addRouteEvent(new RouteEventDiagnostic(
                sequence,
                elapsedMillis(callStartedNanos),
                RouteEventType.CALL_TERMINATED,
                0,
                null,
                null,
                null,
                null,
                termination.name(),
                null,
                remainingBudgetMs,
                null));
        recordBestEffort(diagnostic.complete(
                CallOutcome.FAILED,
                errorType,
                null,
                termination,
                null,
                null,
                false,
                null,
                null,
                usage(null),
                timing(callStartedNanos, startedAt)));
        throw new ModelCallException(
                stableMessage(errorType),
                new ExecutionInfo(logicalCallId, requestId, null, attemptCount, 0, failoverCount),
                new TerminalCause(errorType, null, termination, null, null),
                false);
    }

    private AttemptDiagnostic successfulAttempt(
            int attemptNo,
            int candidateOrder,
            AttemptKind kind,
            int startedSequence,
            int completedSequence,
            ExecutionTarget target,
            ModelProvider provider,
            BreakerState breakerBefore,
            ProviderChatResult result,
            long startedOffsetMs,
            long completedOffsetMs) {
        return new AttemptDiagnostic(
                attemptNo,
                0,
                null,
                null,
                candidateOrder,
                kind,
                startedSequence,
                completedSequence,
                target,
                provider.quotaDomain(),
                breakerBefore,
                breakers.snapshot(target, Capability.CHAT).state(),
                AttemptOutcome.SUCCEEDED,
                null,
                null,
                200,
                result.providerRequestIdMasked(),
                startedOffsetMs,
                null,
                null,
                null,
                Math.max(0, completedOffsetMs - startedOffsetMs),
                inputTokens(result.usage()),
                outputTokens(result.usage()),
                null);
    }

    private AttemptDiagnostic failedAttempt(
            int attemptNo,
            int candidateOrder,
            AttemptKind kind,
            int startedSequence,
            int completedSequence,
            ExecutionTarget target,
            ModelProvider provider,
            BreakerState breakerBefore,
            FailureAction action,
            ProviderFailureException failure,
            long startedOffsetMs,
            long completedOffsetMs) {
        return new AttemptDiagnostic(
                attemptNo,
                0,
                null,
                null,
                candidateOrder,
                kind,
                startedSequence,
                completedSequence,
                target,
                provider.quotaDomain(),
                breakerBefore,
                breakers.snapshot(target, Capability.CHAT).state(),
                attemptOutcome(action.errorType()),
                action.errorType(),
                action.failurePhase(),
                failure.httpStatus(),
                failure.providerRequestIdMasked(),
                startedOffsetMs,
                null,
                null,
                null,
                Math.max(0, completedOffsetMs - startedOffsetMs),
                null,
                null,
                null);
    }

    private RouteEventDiagnostic failoverEvent(
            int sequence,
            long callStartedNanos,
            RouteCandidate next,
            String reason,
            long remainingBudgetMs) {
        return new RouteEventDiagnostic(
                sequence,
                elapsedMillis(callStartedNanos),
                RouteEventType.FAILOVER_SELECTED,
                0,
                null,
                next.plannedOrder(),
                next.deployment().providerId(),
                next.deployment().id(),
                reason,
                null,
                remainingBudgetMs,
                null);
    }

    private CallDiagnosticBuilder diagnosticBuilder(
            UUID logicalCallId, RequestId requestId, ChatCommand command) {
        return new CallDiagnosticBuilder(
                logicalCallId,
                requestId.value(),
                Capability.CHAT,
                command.tier(),
                null,
                false,
                catalog.policyVersion(),
                catalog.configFingerprint());
    }

    private RouteCandidateDiagnostic candidate(RouteCandidate candidate, ChatCommand command) {
        ModelDeployment deployment = candidate.deployment();
        ModelProvider provider = catalog.providers().get(deployment.providerId());
        return new RouteCandidateDiagnostic(
                candidate.candidateOrdinal(),
                target(deployment),
                Capability.CHAT,
                command.tier(),
                provider.quotaDomain(),
                RouteDecision.valueOf(candidate.decision().name()),
                candidate.rejectionReason() == null
                        ? null
                        : RouteRejectionReason.valueOf(candidate.rejectionReason().name()),
                candidate.plannedOrder(),
                deployment.defaultCandidate(),
                deployment.priority(),
                BreakerState.valueOf(candidate.breakerStateSnapshot().name()));
    }

    private BreakerSnapshotState planningState(
            com.app.ai.model.DeploymentId deploymentId, Capability capability) {
        ModelDeployment deployment = catalog.deployments().get(deploymentId);
        return BreakerSnapshotState.valueOf(
                breakers.snapshot(target(deployment), capability).state().name());
    }

    private ChatProvider provider(ProviderId providerId) {
        return Objects.requireNonNull(
                providers.get(providerId), "chat provider is not configured for " + providerId);
    }

    private static ExecutionTarget target(ModelDeployment deployment) {
        return new ExecutionTarget(
                deployment.providerId(), deployment.id(), deployment.modelIdentity());
    }

    private void recordBestEffort(CompletedCallDiagnostic completed) {
        try {
            diagnostics.offer(completed);
        } catch (RuntimeException ignored) {
            // Diagnostics are best effort and must never change the model result.
        }
    }

    private TimingDiagnostic timing(long callStartedNanos, OffsetDateTime startedAt) {
        return new TimingDiagnostic(
                0L,
                null,
                null,
                null,
                elapsedMillis(callStartedNanos),
                startedAt,
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
    }

    private static RequestId requestId(CallOptions options) {
        return options.requestId() == null
                ? new RequestId("request-" + UUID.randomUUID())
                : options.requestId();
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

    private long elapsedMillis(long startedNanos) {
        return Math.max(0, (ticker.read() - startedNanos) / 1_000_000);
    }
}
