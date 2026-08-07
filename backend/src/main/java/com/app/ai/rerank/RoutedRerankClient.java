package com.app.ai.rerank;

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
import com.app.ai.model.ExecutionInfo;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.ModelCallException;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderId;
import com.app.ai.model.RankedCandidate;
import com.app.ai.model.RequestId;
import com.app.ai.model.RerankCandidate;
import com.app.ai.model.RerankClient;
import com.app.ai.model.RerankCommand;
import com.app.ai.model.RerankDegradationReason;
import com.app.ai.model.RerankResult;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.TerminalCause;
import com.app.ai.model.TerminationReason;
import com.app.ai.provider.AttemptFailureType;
import com.app.ai.provider.ProviderAttemptContext;
import com.app.ai.provider.ProviderFailureException;
import com.app.ai.provider.ProviderRankedItem;
import com.app.ai.provider.ProviderRerankRequest;
import com.app.ai.provider.ProviderRerankResult;
import com.app.ai.provider.RerankProvider;
import com.app.ai.routing.BreakerSnapshotState;
import com.app.ai.routing.CandidateRejectionReason;
import com.app.ai.routing.RouteCandidate;
import com.app.ai.routing.RoutePlan;
import com.app.ai.routing.RoutePlanner;
import com.app.ai.routing.RouteRequest;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * architecture.md §11 的 Rerank 端口：共享同步恢复骨架，外加显式 Noop 最终降级。
 *
 * <p>恢复循环与 {@code RoutingChatClient} 同形（breaker 许可 → attempt 预算 → 派发 →
 * 分类 → 原地重试或换候选），差别集中在三处：调用前的语义校验、Provider index 到稳定
 * candidate ID 的映射、以及候选耗尽后的 Noop 而不是抛异常。
 *
 * <p>**Noop 只覆盖三种产品状态**（目录未声明能力、约束下无合格候选、可恢复候选耗尽）。
 * 非法请求、调用方取消、缺 secret 与 401/403 一律直接失败——把配置事故伪装成正常质量下降，
 * 结果就是没人知道重排其实一直没生效。
 */
public final class RoutedRerankClient implements RerankClient {

    private final ModelCatalogSnapshot catalog;
    private final Map<ProviderId, RerankProvider> providers;
    private final ModelBreakerRegistry breakers;
    private final DiagnosticRecorder diagnostics;
    private final RoutePlanner planner;
    private final Clock clock;
    private final NanoTicker ticker;
    private final JitterSource jitter;
    private final Sleeper sleeper;
    private final Duration minimumRequestBudget;

    public RoutedRerankClient(
            ModelCatalogSnapshot catalog,
            Map<ProviderId, RerankProvider> providers,
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

    RoutedRerankClient(
            ModelCatalogSnapshot catalog,
            Map<ProviderId, RerankProvider> providers,
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
    public RerankResult rerank(RerankCommand command) {
        if (command == null) {
            throw invalidRequest(RerankCommand.class.getSimpleName(), null, ServiceTier.BALANCED, 0, 0);
        }
        String validationError = validate(command);
        if (validationError != null) {
            throw invalidRequest(
                    validationError,
                    command.options(),
                    command.tier(),
                    command.candidates().size(),
                    command.topN());
        }

        Call call = new Call(command);
        RoutePlan route = planner.plan(
                catalog,
                RouteRequest.rerank(
                        command.tier(), command.candidates().size(), command.options(), Set.of()),
                (deploymentId, capability) -> planningState(deploymentId, capability));
        route.candidates().forEach(candidate -> call.diagnostic.addCandidate(candidate(candidate, command)));
        call.addRouteCompleted(route);

        if (route.eligibleCandidates().isEmpty()) {
            return degradeBeforeAnyAttempt(call, route);
        }
        return execute(call, route);
    }

    /** 语义校验；返回非 null 表示不合法。ID 唯一与 topN 范围是 api.md §1.6 的硬规则。 */
    private static String validate(RerankCommand command) {
        if (command.candidates().isEmpty()) {
            return "candidates must not be empty";
        }
        Set<String> seen = new HashSet<>();
        for (RerankCandidate candidate : command.candidates()) {
            if (!seen.add(candidate.id())) {
                return "candidate id must be unique within one request";
            }
        }
        if (command.topN() < 1 || command.topN() > command.candidates().size()) {
            return "topN must be between 1 and candidates size";
        }
        return null;
    }

    private RerankResult execute(Call call, RoutePlan route) {
        var limits = catalog.runtimePolicy().rerank();
        ProviderFailureException lastFailure = null;
        candidateLoop:
        for (int candidateIndex = 0;
                candidateIndex < route.eligibleCandidates().size();
                candidateIndex++) {
            RouteCandidate candidate = route.eligibleCandidates().get(candidateIndex);
            ModelDeployment deployment = candidate.deployment();
            ModelProvider modelProvider = catalog.providers().get(deployment.providerId());
            RerankProvider rerankProvider = provider(deployment.providerId());
            ExecutionTarget target = target(deployment);
            int candidateAdmissionCount = 0;

            while (true) {
                BreakerAdmission admission = breakers.tryAcquire(target, Capability.RERANK);
                if (admission instanceof BreakerPermissionRejected rejected) {
                    call.addBreakerRejected(candidate, rejected.state().name());
                    if (candidateIndex + 1 < route.eligibleCandidates().size()) {
                        call.addFailover(
                                route.eligibleCandidates().get(candidateIndex + 1),
                                "BREAKER_PERMISSION_REJECTED");
                        continue candidateLoop;
                    }
                    break;
                }

                BreakerPermission permission = (BreakerPermission) admission;
                var attemptAdmission = call.budget.tryStart(
                        0, deployment.id(), minimumRequestBudget, call.command.options().cancellation());
                if (attemptAdmission.status() != AttemptAdmissionStatus.ADMITTED) {
                    permission.release();
                    if (attemptAdmission.status() == AttemptAdmissionStatus.CANCELED
                            || attemptAdmission.status() == AttemptAdmissionStatus.INTERRUPTED) {
                        throw fail(call, ProviderFailureException.of(AttemptFailureType.CALLER_CANCELED));
                    }
                    if (attemptAdmission.status() == AttemptAdmissionStatus.BUDGET_INSUFFICIENT) {
                        call.addBudgetExhausted(candidate);
                        throw fail(
                                call,
                                ProviderFailureException.of(AttemptFailureType.TOTAL_DEADLINE_EXCEEDED));
                    }
                    break;
                }

                candidateAdmissionCount++;
                int attemptNo = attemptAdmission.logicalAttemptNo();
                AttemptKind kind = kind(candidateAdmissionCount, candidateIndex, call.attemptCount);
                int startedSequence = call.nextSequence();
                AtomicBoolean dispatched = new AtomicBoolean();
                AtomicLong startedOffsetMs = new AtomicLong(call.elapsedMillis());
                var context = new ProviderAttemptContext(
                        call.logicalCallId,
                        attemptNo,
                        call.deadline,
                        call.command.options().cancellation(),
                        () -> {
                            if (dispatched.compareAndSet(false, true)) {
                                startedOffsetMs.set(call.elapsedMillis());
                            }
                        });

                ProviderFailureException failure = null;
                List<RankedCandidate> ranked = null;
                String providerRequestIdMasked = null;
                try {
                    ProviderRerankResult providerResult = rerankProvider.rerank(
                            deployment,
                            new ProviderRerankRequest(
                                    call.command.query(), call.command.candidates(), call.command.topN()),
                            context);
                    if (!dispatched.get() || providerResult == null) {
                        throw ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
                    }
                    providerRequestIdMasked = providerResult.providerRequestIdMasked();
                    ranked = mapToStableIds(providerResult, call.command);
                } catch (RuntimeException providerError) {
                    failure = providerError instanceof ProviderFailureException typed
                            ? typed
                            : ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
                }

                if (failure == null) {
                    long completedOffsetMs = call.elapsedMillis();
                    permission.succeed(Duration.ofMillis(
                            Math.max(0, completedOffsetMs - startedOffsetMs.get())));
                    call.countAttempt(kind);
                    call.diagnostic.addAttempt(attempt(
                            attemptNo, candidate, kind, startedSequence, call.nextSequence(),
                            target, modelProvider, permission.state(), AttemptOutcome.SUCCEEDED,
                            null, null, 200, providerRequestIdMasked,
                            startedOffsetMs.get(), completedOffsetMs));
                    return call.succeed(ranked, candidate, attemptNo, completedOffsetMs);
                }

                FailureAction action = FailureActionPolicy.actionFor(failure.failureType());
                long completedOffsetMs = call.elapsedMillis();
                int completedSequence = call.nextSequence();
                if (!dispatched.get()) {
                    // 请求还没真正发出，不算 attempt，也不结算 breaker。
                    permission.release();
                    throw fail(call, failure);
                }

                permission.fail(
                        Duration.ofMillis(Math.max(0, completedOffsetMs - startedOffsetMs.get())),
                        failure.failureType());
                call.countAttempt(kind);
                lastFailure = failure;
                call.lastAttempted = target;
                call.diagnostic.addAttempt(attempt(
                        attemptNo, candidate, kind, startedSequence, completedSequence,
                        target, modelProvider, permission.state(), attemptOutcome(action.errorType()),
                        action.errorType(), action.failurePhase(), failure.httpStatus(),
                        failure.providerRequestIdMasked(),
                        startedOffsetMs.get(), completedOffsetMs));

                boolean retryCapacity = candidateAdmissionCount < limits.maxSameDeploymentAttempts()
                        && call.attemptCount < limits.maxAttemptsPerWorkUnit()
                        && candidateAdmissionCount == 1;
                Duration retryDelay = retryCapacity ? retryDelay(failure) : null;
                if (retryDelay != null) {
                    call.addRetry(RouteEventType.RETRY_SCHEDULED, attemptNo, candidate,
                            failure.failureType().name(), retryDelay);
                    call.addRetry(RouteEventType.RETRY_WAIT_STARTED, attemptNo, candidate,
                            failure.failureType().name(), retryDelay);
                    WaitOutcome waitOutcome = DeadlineWaiter.waitFor(
                            retryDelay, call.deadline, minimumRequestBudget, sleeper);
                    if (waitOutcome == WaitOutcome.COMPLETED) {
                        continue;
                    }
                    if (waitOutcome == WaitOutcome.BUDGET_INSUFFICIENT) {
                        call.addBudgetExhausted(candidate);
                        throw fail(
                                call,
                                ProviderFailureException.of(AttemptFailureType.TOTAL_DEADLINE_EXCEEDED));
                    }
                    throw fail(call, ProviderFailureException.of(AttemptFailureType.CALLER_CANCELED));
                }

                int nextIndex = call.attemptCount < limits.maxAttemptsPerWorkUnit()
                        ? nextCandidateIndex(route, candidateIndex, modelProvider, action)
                        : -1;
                if (nextIndex >= 0) {
                    call.addFailover(
                            route.eligibleCandidates().get(nextIndex), failure.failureType().name());
                    candidateIndex = nextIndex - 1;
                    continue candidateLoop;
                }
                break candidateLoop;
            }
        }

        // 候选耗尽。只有可恢复失败才降级为 Noop；取消和配置类错误必须原样失败。
        if (lastFailure == null) {
            return degrade(call, RerankDegradationReason.NO_ELIGIBLE_CANDIDATE);
        }
        FailureAction lastAction = FailureActionPolicy.actionFor(lastFailure.failureType());
        if (!recoverable(lastAction.errorType())) {
            throw fail(call, lastFailure);
        }
        return degrade(call, RerankDegradationReason.RECOVERABLE_CANDIDATES_EXHAUSTED);
    }

    /**
     * 没有任何合格候选时区分三种情况：目录里根本没声明 RERANK 能力是允许降级的产品状态；
     * 声明了但缺 secret 是配置故障，必须失败；其余（禁用、等级/地域/调用方约束、文档上限）
     * 是调用约束下无合格候选。
     */
    private RerankResult degradeBeforeAnyAttempt(Call call, RoutePlan route) {
        List<RouteCandidate> declaring = route.candidates().stream()
                .filter(candidate -> candidate.deployment().capabilities().contains(Capability.RERANK))
                .toList();
        if (declaring.isEmpty()) {
            return degrade(call, RerankDegradationReason.CAPABILITY_NOT_DECLARED);
        }
        boolean missingSecret = declaring.stream().anyMatch(candidate ->
                candidate.rejectionReason() == CandidateRejectionReason.CONFIG_INCOMPLETE);
        if (missingSecret) {
            throw fail(call, ProviderFailureException.of(AttemptFailureType.AUTHENTICATION_REJECTED));
        }
        return degrade(call, RerankDegradationReason.NO_ELIGIBLE_CANDIDATE);
    }

    /** Provider 只返回 index，这里校验形状后映射回稳定 ID；越界或重复都是协议违约。 */
    private static List<RankedCandidate> mapToStableIds(
            ProviderRerankResult result, RerankCommand command) {
        List<ProviderRankedItem> items = result.items();
        if (items.size() > command.topN()) {
            throw ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
        }
        Set<Integer> seen = new HashSet<>();
        List<RankedCandidate> ranked = new ArrayList<>(items.size());
        for (ProviderRankedItem item : items) {
            if (item.index() < 0
                    || item.index() >= command.candidates().size()
                    || !seen.add(item.index())) {
                throw ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
            }
            ranked.add(new RankedCandidate(
                    command.candidates().get(item.index()).id(),
                    item.index(),
                    item.relevanceScore()));
        }
        return List.copyOf(ranked);
    }

    private static boolean recoverable(ModelErrorType errorType) {
        return switch (errorType) {
            case RATE_LIMITED, NETWORK, SERVER_ERROR, TIMEOUT, PROTOCOL_CONTRACT -> true;
            default -> false;
        };
    }

    private static AttemptKind kind(int candidateAdmissionCount, int candidateIndex, int attemptCount) {
        if (candidateAdmissionCount > 1) {
            return AttemptKind.RETRY;
        }
        return candidateIndex > 0 || attemptCount > 0 ? AttemptKind.FAILOVER : AttemptKind.INITIAL;
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
        return action.mayRetrySameCandidate(false) ? FullJitterBackoff.delay(1, jitter) : null;
    }

    private int nextCandidateIndex(
            RoutePlan route, int currentIndex, ModelProvider currentProvider, FailureAction action) {
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

    private RerankResult degrade(Call call, RerankDegradationReason reason) {
        call.addTerminated(TerminationReason.NOOP_DEGRADED);
        recordBestEffort(call.diagnostic.complete(
                CallOutcome.DEGRADED,
                null,
                null,
                TerminationReason.NOOP_DEGRADED,
                null,
                null,
                false,
                false,
                reason,
                call.usage(),
                call.timing()));
        List<RankedCandidate> items = new ArrayList<>(call.command.topN());
        for (int index = 0; index < call.command.topN(); index++) {
            items.add(new RankedCandidate(call.command.candidates().get(index).id(), index, null));
        }
        return new RerankResult(items, false, reason, call.execution(null));
    }

    private ModelCallException fail(Call call, ProviderFailureException failure) {
        FailureAction action = FailureActionPolicy.actionFor(failure.failureType());
        TerminationReason termination =
                FailureActionPolicy.terminationReason(failure.failureType(), false);
        call.addTerminated(termination);
        CallOutcome outcome = action.errorType() == ModelErrorType.CANCELED
                ? CallOutcome.CANCELED
                : CallOutcome.FAILED;
        recordBestEffort(call.diagnostic.complete(
                outcome,
                action.errorType(),
                action.failurePhase(),
                termination,
                failure.httpStatus(),
                null,
                false,
                null,
                null,
                call.usage(),
                call.timing()));
        return new ModelCallException(
                stableMessage(action.errorType()),
                call.execution(null),
                new TerminalCause(
                        action.errorType(),
                        action.failurePhase(),
                        termination,
                        failure.httpStatus(),
                        call.lastAttempted),
                retryableByCaller(action.errorType()));
    }

    private ModelCallException invalidRequest(
            String reason, CallOptions options, ServiceTier tier, int documentCount, int topN) {
        UUID logicalCallId = UUID.randomUUID();
        RequestId requestId = requestId(options);
        long startedNanos = ticker.read();
        OffsetDateTime startedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        var diagnostic = new CallDiagnosticBuilder(
                logicalCallId,
                requestId.value(),
                Capability.RERANK,
                tier,
                null,
                false,
                catalog.policyVersion(),
                catalog.configFingerprint());
        diagnostic.addRouteEvent(new RouteEventDiagnostic(
                1,
                0,
                RouteEventType.CALL_TERMINATED,
                0,
                null,
                null,
                null,
                null,
                TerminationReason.INVALID_REQUEST.name(),
                null,
                catalog.runtimePolicy().rerank().totalTimeout().toMillis(),
                null));
        recordBestEffort(diagnostic.complete(
                CallOutcome.FAILED,
                ModelErrorType.INVALID_REQUEST,
                null,
                TerminationReason.INVALID_REQUEST,
                null,
                null,
                false,
                null,
                null,
                new UsageDiagnostic(
                        null, null, null, null, null,
                        documentCount == 0 ? null : documentCount,
                        topN == 0 ? null : topN,
                        null),
                new TimingDiagnostic(
                        0L, null, null, null,
                        Math.max(0, (ticker.read() - startedNanos) / 1_000_000),
                        startedAt,
                        OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC))));
        // reason 只进异常消息的上下文，不进诊断：诊断不保存 query/候选正文，也不保存原始原因串。
        return new ModelCallException(
                stableMessage(ModelErrorType.INVALID_REQUEST) + "：" + reason,
                new ExecutionInfo(logicalCallId, requestId, null, 0, 0, 0),
                new TerminalCause(
                        ModelErrorType.INVALID_REQUEST,
                        null,
                        TerminationReason.INVALID_REQUEST,
                        null,
                        null),
                false);
    }

    private AttemptDiagnostic attempt(
            int attemptNo,
            RouteCandidate candidate,
            AttemptKind kind,
            int startedSequence,
            int completedSequence,
            ExecutionTarget target,
            ModelProvider provider,
            BreakerState breakerBefore,
            AttemptOutcome outcome,
            ModelErrorType errorType,
            com.app.ai.model.FailurePhase failurePhase,
            Integer httpStatus,
            String providerRequestIdMasked,
            long startedOffsetMs,
            long completedOffsetMs) {
        return new AttemptDiagnostic(
                attemptNo,
                0,
                null,
                null,
                candidate.plannedOrder(),
                kind,
                startedSequence,
                completedSequence,
                target,
                provider.quotaDomain(),
                breakerBefore,
                breakers.snapshot(target, Capability.RERANK).state(),
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

    private RouteCandidateDiagnostic candidate(RouteCandidate candidate, RerankCommand command) {
        ModelDeployment deployment = candidate.deployment();
        ModelProvider provider = catalog.providers().get(deployment.providerId());
        return new RouteCandidateDiagnostic(
                candidate.candidateOrdinal(),
                target(deployment),
                Capability.RERANK,
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

    private RerankProvider provider(ProviderId providerId) {
        return Objects.requireNonNull(
                providers.get(providerId), "rerank provider is not configured for " + providerId);
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

    private static RequestId requestId(CallOptions options) {
        return options == null || options.requestId() == null
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

    /** 一次逻辑调用的可变状态与诊断事件序号，避免把十几个参数在私有方法间来回传。 */
    private final class Call {

        private final RerankCommand command;
        private final UUID logicalCallId = UUID.randomUUID();
        private final RequestId requestId;
        private final long startedNanos;
        private final OffsetDateTime startedAt;
        private final MonotonicDeadline deadline;
        private final AttemptBudget budget;
        private final CallDiagnosticBuilder diagnostic;

        private int sequence;
        private int attemptCount;
        private int retryCount;
        private int failoverCount;
        private ExecutionTarget lastAttempted;

        private Call(RerankCommand command) {
            this.command = command;
            this.requestId = RoutedRerankClient.requestId(command.options());
            this.startedNanos = ticker.read();
            this.startedAt = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
            var limits = catalog.runtimePolicy().rerank();
            this.deadline = MonotonicDeadline.start(
                    limits.totalTimeout(), command.options().timeout(), ticker);
            this.budget = new AttemptBudget(
                    deadline, limits.maxAttemptsPerWorkUnit(), limits.maxSameDeploymentAttempts());
            this.diagnostic = new CallDiagnosticBuilder(
                    logicalCallId,
                    requestId.value(),
                    Capability.RERANK,
                    command.tier(),
                    null,
                    false,
                    catalog.policyVersion(),
                    catalog.configFingerprint());
        }

        private int nextSequence() {
            return ++sequence;
        }

        private long elapsedMillis() {
            return Math.max(0, (ticker.read() - startedNanos) / 1_000_000);
        }

        private void countAttempt(AttemptKind kind) {
            attemptCount++;
            if (kind == AttemptKind.RETRY) {
                retryCount++;
            } else if (kind == AttemptKind.FAILOVER) {
                failoverCount++;
            }
        }

        private void addRouteCompleted(RoutePlan route) {
            diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    nextSequence(), elapsedMillis(), RouteEventType.ROUTE_COMPLETED,
                    null, null, null, null, null, null, null,
                    deadline.remaining().toMillis(), route.eligibleCandidates().size()));
        }

        private void addBreakerRejected(RouteCandidate candidate, String state) {
            diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    nextSequence(), elapsedMillis(), RouteEventType.BREAKER_PERMISSION_REJECTED,
                    0, null, candidate.plannedOrder(),
                    candidate.deployment().providerId(), candidate.deployment().id(),
                    state, null, deadline.remaining().toMillis(), null));
        }

        private void addFailover(RouteCandidate next, String reason) {
            diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    nextSequence(), elapsedMillis(), RouteEventType.FAILOVER_SELECTED,
                    0, null, next.plannedOrder(),
                    next.deployment().providerId(), next.deployment().id(),
                    reason, null, deadline.remaining().toMillis(), null));
        }

        private void addRetry(
                RouteEventType type, int attemptNo, RouteCandidate candidate,
                String reason, Duration wait) {
            diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    nextSequence(), elapsedMillis(), type,
                    0, attemptNo, candidate.plannedOrder(),
                    candidate.deployment().providerId(), candidate.deployment().id(),
                    reason, wait.toMillis(), deadline.remaining().toMillis(), null));
        }

        private void addBudgetExhausted(RouteCandidate candidate) {
            diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    nextSequence(), elapsedMillis(), RouteEventType.BUDGET_EXHAUSTED,
                    0, null, candidate.plannedOrder(),
                    candidate.deployment().providerId(), candidate.deployment().id(),
                    AttemptAdmissionStatus.BUDGET_INSUFFICIENT.name(), null,
                    deadline.remaining().toMillis(), null));
        }

        private void addTerminated(TerminationReason termination) {
            diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    nextSequence(), elapsedMillis(), RouteEventType.CALL_TERMINATED,
                    0, attemptCount == 0 ? null : attemptCount, null,
                    lastAttempted == null ? null : lastAttempted.providerId(),
                    lastAttempted == null ? null : lastAttempted.deploymentId(),
                    termination.name(), null, deadline.remaining().toMillis(), null));
        }

        private RerankResult succeed(
                List<RankedCandidate> ranked,
                RouteCandidate candidate,
                int attemptNo,
                long completedOffsetMs) {
            ExecutionTarget target = target(candidate.deployment());
            diagnostic.addRouteEvent(new RouteEventDiagnostic(
                    nextSequence(), completedOffsetMs, RouteEventType.CALL_TERMINATED,
                    0, attemptNo, candidate.plannedOrder(),
                    candidate.deployment().providerId(), candidate.deployment().id(),
                    TerminationReason.COMPLETED.name(), null,
                    deadline.remaining().toMillis(), null));
            recordBestEffort(diagnostic.complete(
                    CallOutcome.SUCCEEDED, null, null, TerminationReason.COMPLETED,
                    200, null, false, true, null, usage(), timing()));
            return new RerankResult(ranked, true, null, execution(target));
        }

        private ExecutionInfo execution(ExecutionTarget actual) {
            return new ExecutionInfo(
                    logicalCallId, requestId, actual, attemptCount, retryCount, failoverCount);
        }

        private UsageDiagnostic usage() {
            return new UsageDiagnostic(
                    null, null, null, null, null,
                    command.candidates().size(), command.topN(), null);
        }

        private TimingDiagnostic timing() {
            return new TimingDiagnostic(
                    0L, null, null, null, elapsedMillis(), startedAt,
                    OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
        }
    }
}
