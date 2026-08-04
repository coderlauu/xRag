package com.app.ai.embedding;

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
import com.app.ai.diagnostic.EmbeddingBatchExecutionDiagnostic;
import com.app.ai.diagnostic.RouteCandidateDiagnostic;
import com.app.ai.diagnostic.RouteDecision;
import com.app.ai.diagnostic.RouteEventDiagnostic;
import com.app.ai.diagnostic.RouteEventType;
import com.app.ai.diagnostic.RouteRejectionReason;
import com.app.ai.diagnostic.TimingDiagnostic;
import com.app.ai.diagnostic.UsageDiagnostic;
import com.app.ai.model.CallOptions;
import com.app.ai.model.Capability;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.EmbeddingBatchExecution;
import com.app.ai.model.EmbeddingClient;
import com.app.ai.model.EmbeddingCommand;
import com.app.ai.model.EmbeddingExecutionInfo;
import com.app.ai.model.EmbeddingResult;
import com.app.ai.model.ExecutionInfo;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.FailurePhase;
import com.app.ai.model.ModelCallException;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderId;
import com.app.ai.model.RequestId;
import com.app.ai.model.TerminalCause;
import com.app.ai.model.TerminationReason;
import com.app.ai.provider.AttemptFailureType;
import com.app.ai.provider.EmbeddingProvider;
import com.app.ai.provider.IndexedEmbedding;
import com.app.ai.provider.ProviderAttemptContext;
import com.app.ai.provider.ProviderEmbeddingResult;
import com.app.ai.provider.ProviderFailureException;
import com.app.ai.routing.BreakerSnapshotState;
import com.app.ai.routing.RouteCandidate;
import com.app.ai.routing.RoutePlan;
import com.app.ai.routing.RoutePlanner;
import com.app.ai.routing.RouteRequest;
import com.app.ai.routing.WorkUnitRange;
import com.app.ai.runtime.AttemptAdmission;
import com.app.ai.runtime.AttemptAdmissionStatus;
import com.app.ai.runtime.AttemptBudget;
import com.app.ai.runtime.DeadlineWaiter;
import com.app.ai.runtime.FailureAction;
import com.app.ai.runtime.FailureActionPolicy;
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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Embedding entry point for the shared scheduling layer.
 *
 * <p>A logical call is split into work units by the smallest {@code maxBatchItems} among the
 * eligible equivalent deployments, so any batch can be handed to any candidate unchanged. Each
 * batch recovers independently and already-completed batches are never recomputed, which means one
 * successful call may be contributed to by several deployments.
 */
public final class RoutedEmbeddingClient implements EmbeddingClient {

    private final ModelCatalogSnapshot catalog;
    private final Map<ProviderId, EmbeddingProvider> providers;
    private final ModelBreakerRegistry breakers;
    private final DiagnosticRecorder diagnostics;
    private final RoutePlanner planner;
    private final Clock clock;
    private final NanoTicker ticker;
    private final JitterSource jitter;
    private final Sleeper sleeper;
    private final Duration minimumRequestBudget;

    public RoutedEmbeddingClient(
            ModelCatalogSnapshot catalog,
            Map<ProviderId, EmbeddingProvider> providers,
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

    RoutedEmbeddingClient(
            ModelCatalogSnapshot catalog,
            Map<ProviderId, EmbeddingProvider> providers,
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
    public EmbeddingResult embed(EmbeddingCommand command) {
        if (command == null) {
            throw new EmbeddingCall(null).failInvalidRequest();
        }
        return new EmbeddingCall(command).run();
    }

    private final class EmbeddingCall {

        private final EmbeddingCommand command;
        private final ModelIdentity identity;
        private final List<String> texts;
        private final CallOptions options;
        private final UUID logicalCallId = UUID.randomUUID();
        private final RequestId requestId;
        private final long callStartedNanos = ticker.read();
        private final OffsetDateTime startedAt =
                OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        private final CallDiagnosticBuilder diagnostic;

        private int sequence;
        private int attemptCount;
        private int retryCount;
        private int failoverCount;

        private EmbeddingCall(EmbeddingCommand command) {
            this.command = command;
            this.identity = command == null ? null : command.modelIdentity();
            this.texts = command == null ? List.of() : command.texts();
            this.options = command == null ? CallOptions.defaults() : command.options();
            this.requestId = requestId(options);
            this.diagnostic = new CallDiagnosticBuilder(
                    logicalCallId,
                    requestId.value(),
                    Capability.EMBEDDING,
                    null,
                    identity,
                    false,
                    catalog.policyVersion(),
                    catalog.configFingerprint());
        }

        private EmbeddingResult run() {
            var limits = catalog.runtimePolicy().embedding();
            var deadline = MonotonicDeadline.start(
                    limits.totalTimeout(), options.timeout(), ticker);
            var attemptBudget = new AttemptBudget(
                    deadline, limits.maxAttemptsPerWorkUnit(), limits.maxSameDeploymentAttempts());
            RoutePlan route = planner.plan(
                    catalog,
                    RouteRequest.embedding(identity, texts.size(), options, Set.of()),
                    this::planningState);
            route.candidates().forEach(candidate -> diagnostic.addCandidate(candidate(candidate)));
            diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    ++sequence,
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

            if (route.eligibleCandidates().isEmpty()) {
                throw failBeforeAttempt(
                        ModelErrorType.NO_ELIGIBLE_MODEL, TerminationReason.NO_ELIGIBLE_MODEL);
            }
            int dimensions = route.eligibleCandidates().get(0).deployment().embedding().dimensions();
            if (texts.isEmpty()) {
                return complete(List.of(), List.of(), dimensions, 0);
            }

            var vectors = new ArrayList<float[]>(texts.size());
            var batches = new ArrayList<EmbeddingBatchExecution>(route.workUnits().size());
            int candidateIndex = 0;
            for (WorkUnitRange unit : route.workUnits()) {
                BatchOutcome outcome = runWorkUnit(
                        unit, candidateIndex, route, attemptBudget, deadline, dimensions);
                candidateIndex = outcome.candidateIndex();
                vectors.addAll(outcome.vectors());
                batches.add(outcome.execution());
            }
            return complete(vectors, batches, dimensions, route.workUnits().size());
        }

        /**
         * Runs one batch, starting from the candidate that last contributed so a known-bad
         * deployment is not re-attempted for every remaining batch.
         */
        private BatchOutcome runWorkUnit(
                WorkUnitRange unit,
                int startCandidateIndex,
                RoutePlan route,
                AttemptBudget attemptBudget,
                MonotonicDeadline deadline,
                int dimensions) {
            List<String> batchTexts = texts.subList(
                    unit.inputFromInclusive(), unit.inputToExclusive());
            ProviderFailureException lastFailure = null;
            ExecutionTarget lastAttempted = null;
            int batchAttempts = 0;
            int batchRetries = 0;
            int batchFailovers = 0;

            candidateLoop:
            for (int candidateIndex = startCandidateIndex;
                    candidateIndex < route.eligibleCandidates().size();
                    candidateIndex++) {
                RouteCandidate candidate = route.eligibleCandidates().get(candidateIndex);
                ModelDeployment deployment = candidate.deployment();
                ModelProvider modelProvider = catalog.providers().get(deployment.providerId());
                ExecutionTarget target = target(deployment);
                int candidateAdmissionCount = 0;

                while (true) {
                    BreakerAdmission admission = breakers.tryAcquire(target, Capability.EMBEDDING);
                    if (admission instanceof BreakerPermissionRejected rejected) {
                        addRouteEvent(
                                RouteEventType.BREAKER_PERMISSION_REJECTED,
                                unit,
                                null,
                                candidate,
                                rejected.state().name(),
                                null,
                                deadline);
                        if (candidateIndex + 1 < route.eligibleCandidates().size()) {
                            addRouteEvent(
                                    RouteEventType.FAILOVER_SELECTED,
                                    unit,
                                    null,
                                    route.eligibleCandidates().get(candidateIndex + 1),
                                    "BREAKER_PERMISSION_REJECTED",
                                    null,
                                    deadline);
                            continue candidateLoop;
                        }
                        break;
                    }

                    BreakerPermission permission = (BreakerPermission) admission;
                    AttemptAdmission budget = attemptBudget.tryStart(
                            unit.workUnitIndex(),
                            deployment.id(),
                            minimumRequestBudget,
                            options.cancellation());
                    if (budget.status() != AttemptAdmissionStatus.ADMITTED) {
                        permission.release();
                        if (budget.status() == AttemptAdmissionStatus.CANCELED
                                || budget.status() == AttemptAdmissionStatus.INTERRUPTED) {
                            throw fail(ProviderFailureException.of(
                                    AttemptFailureType.CALLER_CANCELED), lastAttempted, deadline);
                        }
                        if (budget.status() == AttemptAdmissionStatus.BUDGET_INSUFFICIENT) {
                            addRouteEvent(
                                    RouteEventType.BUDGET_EXHAUSTED,
                                    unit,
                                    null,
                                    candidate,
                                    budget.status().name(),
                                    null,
                                    deadline);
                            throw fail(
                                    ProviderFailureException.of(
                                            AttemptFailureType.TOTAL_DEADLINE_EXCEEDED),
                                    lastAttempted,
                                    deadline);
                        }
                        break;
                    }

                    candidateAdmissionCount++;
                    AttemptKind kind;
                    if (candidateAdmissionCount > 1) {
                        kind = AttemptKind.RETRY;
                    } else if (candidateIndex > startCandidateIndex || batchAttempts > 0) {
                        kind = AttemptKind.FAILOVER;
                    } else {
                        kind = AttemptKind.INITIAL;
                    }
                    int startedSequence = ++sequence;
                    var dispatched = new AtomicBoolean();
                    var attemptStartedOffsetMs = new AtomicLong(elapsedMillis());
                    var context = new ProviderAttemptContext(
                            logicalCallId,
                            budget.logicalAttemptNo(),
                            deadline,
                            options.cancellation(),
                            () -> {
                                if (dispatched.compareAndSet(false, true)) {
                                    attemptStartedOffsetMs.set(elapsedMillis());
                                }
                            });

                    List<float[]> batchVectors;
                    ProviderEmbeddingResult providerResult;
                    try {
                        providerResult = provider(deployment.providerId())
                                .embed(deployment, batchTexts, context);
                        if (!dispatched.get() || providerResult == null) {
                            throw ProviderFailureException.of(
                                    AttemptFailureType.PROVIDER_RESPONSE_INVALID);
                        }
                        batchVectors = orderedVectors(
                                providerResult, batchTexts.size(), dimensions);
                    } catch (RuntimeException providerError) {
                        ProviderFailureException failure =
                                providerError instanceof ProviderFailureException typed
                                        ? typed
                                        : ProviderFailureException.of(
                                                AttemptFailureType.PROVIDER_RESPONSE_INVALID);
                        FailureAction action = FailureActionPolicy.actionFor(failure.failureType());
                        long completedOffsetMs = elapsedMillis();
                        int completedSequence = ++sequence;
                        if (!dispatched.get()) {
                            permission.release();
                            throw fail(failure, lastAttempted, deadline);
                        }

                        permission.fail(
                                Duration.ofMillis(Math.max(
                                        0, completedOffsetMs - attemptStartedOffsetMs.get())),
                                failure.failureType());
                        attemptCount++;
                        batchAttempts++;
                        if (kind == AttemptKind.RETRY) {
                            retryCount++;
                            batchRetries++;
                        } else if (kind == AttemptKind.FAILOVER) {
                            failoverCount++;
                            batchFailovers++;
                        }
                        lastFailure = failure;
                        lastAttempted = target;
                        diagnostic.addAttempt(attemptDiagnostic(
                                budget.logicalAttemptNo(),
                                unit,
                                candidate,
                                kind,
                                startedSequence,
                                completedSequence,
                                target,
                                modelProvider,
                                permission.state(),
                                attemptOutcome(action.errorType()),
                                action.errorType(),
                                action.failurePhase(),
                                failure.httpStatus(),
                                failure.providerRequestIdMasked(),
                                attemptStartedOffsetMs.get(),
                                completedOffsetMs));

                        var limits = catalog.runtimePolicy().embedding();
                        boolean retryCapacity = candidateAdmissionCount == 1
                                && candidateAdmissionCount < limits.maxSameDeploymentAttempts()
                                && budget.workUnitAttemptNo() < limits.maxAttemptsPerWorkUnit();
                        Duration retryDelay = retryCapacity ? retryDelay(failure) : null;
                        if (retryDelay != null) {
                            addRouteEvent(
                                    RouteEventType.RETRY_SCHEDULED,
                                    unit,
                                    budget.logicalAttemptNo(),
                                    candidate,
                                    failure.failureType().name(),
                                    retryDelay,
                                    deadline);
                            addRouteEvent(
                                    RouteEventType.RETRY_WAIT_STARTED,
                                    unit,
                                    budget.logicalAttemptNo(),
                                    candidate,
                                    failure.failureType().name(),
                                    retryDelay,
                                    deadline);
                            WaitOutcome waitOutcome = DeadlineWaiter.waitFor(
                                    retryDelay, deadline, minimumRequestBudget, sleeper);
                            if (waitOutcome == WaitOutcome.COMPLETED) {
                                continue;
                            }
                            if (waitOutcome == WaitOutcome.BUDGET_INSUFFICIENT) {
                                addRouteEvent(
                                        RouteEventType.BUDGET_EXHAUSTED,
                                        unit,
                                        null,
                                        candidate,
                                        AttemptAdmissionStatus.BUDGET_INSUFFICIENT.name(),
                                        null,
                                        deadline);
                                throw fail(
                                        ProviderFailureException.of(
                                                AttemptFailureType.TOTAL_DEADLINE_EXCEEDED),
                                        lastAttempted,
                                        deadline);
                            }
                            throw fail(
                                    ProviderFailureException.of(AttemptFailureType.CALLER_CANCELED),
                                    lastAttempted,
                                    deadline);
                        }

                        int nextIndex = nextCandidateIndex(
                                route, candidateIndex, modelProvider, action);
                        if (nextIndex >= 0) {
                            addRouteEvent(
                                    RouteEventType.FAILOVER_SELECTED,
                                    unit,
                                    null,
                                    route.eligibleCandidates().get(nextIndex),
                                    failure.failureType().name(),
                                    null,
                                    deadline);
                            candidateIndex = nextIndex - 1;
                            continue candidateLoop;
                        }
                        throw fail(failure, lastAttempted, deadline);
                    }

                    long completedOffsetMs = elapsedMillis();
                    int completedSequence = ++sequence;
                    permission.succeed(Duration.ofMillis(Math.max(
                            0, completedOffsetMs - attemptStartedOffsetMs.get())));
                    attemptCount++;
                    batchAttempts++;
                    if (kind == AttemptKind.RETRY) {
                        retryCount++;
                        batchRetries++;
                    } else if (kind == AttemptKind.FAILOVER) {
                        failoverCount++;
                        batchFailovers++;
                    }
                    diagnostic.addAttempt(attemptDiagnostic(
                            budget.logicalAttemptNo(),
                            unit,
                            candidate,
                            kind,
                            startedSequence,
                            completedSequence,
                            target,
                            modelProvider,
                            permission.state(),
                            AttemptOutcome.SUCCEEDED,
                            null,
                            null,
                            200,
                            providerResult.providerRequestIdMasked(),
                            attemptStartedOffsetMs.get(),
                            completedOffsetMs));
                    var execution = new EmbeddingBatchExecution(
                            unit.workUnitIndex(),
                            unit.inputFromInclusive(),
                            unit.inputToExclusive(),
                            target,
                            batchAttempts,
                            batchRetries,
                            batchFailovers);
                    diagnostic.addEmbeddingBatch(new EmbeddingBatchExecutionDiagnostic(
                            execution.workUnitIndex(),
                            execution.inputFromInclusive(),
                            execution.inputToExclusive(),
                            target,
                            batchAttempts,
                            batchRetries,
                            batchFailovers));
                    return new BatchOutcome(candidateIndex, batchVectors, execution);
                }
            }

            throw lastFailure == null
                    ? failBeforeAttempt(
                            ModelErrorType.NO_ELIGIBLE_MODEL, TerminationReason.NO_ELIGIBLE_MODEL)
                    : fail(lastFailure, lastAttempted, deadline);
        }

        /**
         * Restores provider output into request order, rejecting duplicate, out-of-range or
         * missing indexes, wrong dimensions and non-finite components.
         */
        private List<float[]> orderedVectors(
                ProviderEmbeddingResult result, int expectedCount, int dimensions) {
            List<IndexedEmbedding> embeddings = result.embeddings();
            if (embeddings.size() != expectedCount) {
                throw ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
            }
            var ordered = new float[expectedCount][];
            for (IndexedEmbedding embedding : embeddings) {
                int index = embedding.index();
                if (index >= expectedCount || ordered[index] != null) {
                    throw ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
                }
                float[] vector = embedding.vector();
                if (vector.length != dimensions) {
                    throw ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
                }
                for (float component : vector) {
                    if (!Float.isFinite(component)) {
                        throw ProviderFailureException.of(
                                AttemptFailureType.PROVIDER_RESPONSE_INVALID);
                    }
                }
                ordered[index] = vector;
            }
            return List.of(ordered);
        }

        private EmbeddingResult complete(
                List<float[]> vectors,
                List<EmbeddingBatchExecution> batches,
                int dimensions,
                int batchCount) {
            var contributors = new LinkedHashSet<ExecutionTarget>();
            batches.forEach(batch -> contributors.add(batch.actualExecution()));
            diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    ++sequence,
                    elapsedMillis(),
                    RouteEventType.CALL_TERMINATED,
                    null,
                    attemptCount == 0 ? null : attemptCount,
                    null,
                    null,
                    null,
                    TerminationReason.COMPLETED.name(),
                    null,
                    null,
                    null));
            recordBestEffort(diagnostic.complete(
                    CallOutcome.SUCCEEDED,
                    null,
                    null,
                    TerminationReason.COMPLETED,
                    200,
                    null,
                    false,
                    null,
                    null,
                    new UsageDiagnostic(
                            null, null, texts.size(), batchCount, dimensions, null, null, null),
                    timing()));
            return new EmbeddingResult(
                    vectors,
                    identity,
                    dimensions,
                    new EmbeddingExecutionInfo(
                            logicalCallId,
                            requestId,
                            attemptCount,
                            retryCount,
                            failoverCount,
                            contributors.size() > 1,
                            List.copyOf(contributors),
                            batches));
        }

        private ModelCallException fail(
                ProviderFailureException failure,
                ExecutionTarget lastAttempted,
                MonotonicDeadline deadline) {
            FailureAction action = FailureActionPolicy.actionFor(failure.failureType());
            TerminationReason termination = FailureActionPolicy.terminationReason(
                    failure.failureType(), false);
            diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    ++sequence,
                    elapsedMillis(),
                    RouteEventType.CALL_TERMINATED,
                    null,
                    attemptCount == 0 ? null : attemptCount,
                    null,
                    lastAttempted == null ? null : lastAttempted.providerId(),
                    lastAttempted == null ? null : lastAttempted.deploymentId(),
                    termination.name(),
                    null,
                    deadline.remaining().toMillis(),
                    null));
            recordBestEffort(diagnostic.complete(
                    action.errorType() == ModelErrorType.CANCELED
                            ? CallOutcome.CANCELED
                            : CallOutcome.FAILED,
                    action.errorType(),
                    action.failurePhase(),
                    termination,
                    failure.httpStatus(),
                    null,
                    false,
                    null,
                    null,
                    new UsageDiagnostic(
                            null, null, texts.size(), null, null, null, null, null),
                    timing()));
            return new ModelCallException(
                    stableMessage(action.errorType()),
                    executionInfo(),
                    new TerminalCause(
                            action.errorType(),
                            action.failurePhase(),
                            termination,
                            failure.httpStatus(),
                            lastAttempted),
                    retryableByCaller(action.errorType()));
        }

        private ModelCallException failBeforeAttempt(
                ModelErrorType errorType, TerminationReason termination) {
            diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    ++sequence,
                    elapsedMillis(),
                    RouteEventType.CALL_TERMINATED,
                    null,
                    null,
                    null,
                    null,
                    null,
                    termination.name(),
                    null,
                    null,
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
                    new UsageDiagnostic(
                            null, null, texts.size(), null, null, null, null, null),
                    timing()));
            return new ModelCallException(
                    stableMessage(errorType),
                    executionInfo(),
                    new TerminalCause(errorType, null, termination, null, null),
                    false);
        }

        private ModelCallException failInvalidRequest() {
            return failBeforeAttempt(
                    ModelErrorType.INVALID_REQUEST, TerminationReason.INVALID_REQUEST);
        }

        /** Failures expose only the shared failure execution info; no batch ever contributed. */
        private ExecutionInfo executionInfo() {
            return new ExecutionInfo(
                    logicalCallId, requestId, null, attemptCount, retryCount, failoverCount);
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

        private AttemptDiagnostic attemptDiagnostic(
                int attemptNo,
                WorkUnitRange unit,
                RouteCandidate candidate,
                AttemptKind kind,
                int startedSequence,
                int completedSequence,
                ExecutionTarget target,
                ModelProvider modelProvider,
                BreakerState breakerBefore,
                AttemptOutcome outcome,
                ModelErrorType errorType,
                FailurePhase failurePhase,
                Integer httpStatus,
                String providerRequestIdMasked,
                long startedOffsetMs,
                long completedOffsetMs) {
            return new AttemptDiagnostic(
                    attemptNo,
                    unit.workUnitIndex(),
                    unit.inputFromInclusive(),
                    unit.inputToExclusive(),
                    candidate.plannedOrder(),
                    kind,
                    startedSequence,
                    completedSequence,
                    target,
                    modelProvider.quotaDomain(),
                    breakerBefore,
                    breakers.snapshot(target, Capability.EMBEDDING).state(),
                    outcome,
                    errorType,
                    failurePhase,
                    httpStatus,
                    providerRequestIdMasked,
                    startedOffsetMs,
                    null,
                    null,
                    null,
                    Math.max(0, completedOffsetMs - startedOffsetMs),
                    null,
                    null,
                    null);
        }

        private void addRouteEvent(
                RouteEventType eventType,
                WorkUnitRange unit,
                Integer attemptNo,
                RouteCandidate candidate,
                String reason,
                Duration wait,
                MonotonicDeadline deadline) {
            diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    ++sequence,
                    elapsedMillis(),
                    eventType,
                    unit.workUnitIndex(),
                    attemptNo,
                    candidate.plannedOrder(),
                    candidate.deployment().providerId(),
                    candidate.deployment().id(),
                    reason,
                    wait == null ? null : wait.toMillis(),
                    deadline.remaining().toMillis(),
                    null));
        }

        private RouteCandidateDiagnostic candidate(RouteCandidate candidate) {
            ModelDeployment deployment = candidate.deployment();
            ModelProvider modelProvider = catalog.providers().get(deployment.providerId());
            return new RouteCandidateDiagnostic(
                    candidate.candidateOrdinal(),
                    target(deployment),
                    Capability.EMBEDDING,
                    null,
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

        private BreakerSnapshotState planningState(
                DeploymentId deploymentId, Capability capability) {
            ModelDeployment deployment = catalog.deployments().get(deploymentId);
            return BreakerSnapshotState.valueOf(
                    breakers.snapshot(target(deployment), capability).state().name());
        }

        private TimingDiagnostic timing() {
            return new TimingDiagnostic(
                    0L,
                    null,
                    null,
                    null,
                    elapsedMillis(),
                    startedAt,
                    OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
        }

        private long elapsedMillis() {
            return Math.max(0, (ticker.read() - callStartedNanos) / 1_000_000);
        }
    }

    private record BatchOutcome(
            int candidateIndex, List<float[]> vectors, EmbeddingBatchExecution execution) {
    }

    private EmbeddingProvider provider(ProviderId providerId) {
        return Objects.requireNonNull(
                providers.get(providerId),
                "embedding provider is not configured for " + providerId);
    }

    private void recordBestEffort(CompletedCallDiagnostic completed) {
        try {
            diagnostics.offer(completed);
        } catch (RuntimeException ignored) {
            // Diagnostics are best effort and must never change the model result.
        }
    }

    private static ExecutionTarget target(ModelDeployment deployment) {
        return new ExecutionTarget(
                deployment.providerId(), deployment.id(), deployment.modelIdentity());
    }

    private static RequestId requestId(CallOptions options) {
        return options.requestId() == null
                ? new RequestId("request-" + UUID.randomUUID())
                : options.requestId();
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
}
