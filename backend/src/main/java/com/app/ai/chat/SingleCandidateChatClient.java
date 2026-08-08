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
import com.app.ai.model.ChatClient;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatResult;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ExecutionInfo;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelCallException;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.RequestId;
import com.app.ai.model.TerminalCause;
import com.app.ai.model.TerminationReason;
import com.app.ai.model.TokenUsage;
import com.app.ai.provider.ChatProvider;
import com.app.ai.provider.AttemptFailureType;
import com.app.ai.provider.ProviderAttemptContext;
import com.app.ai.provider.ProviderChatRequest;
import com.app.ai.provider.ProviderFailureException;
import com.app.ai.runtime.FailureAction;
import com.app.ai.runtime.FailureActionPolicy;
import com.app.ai.runtime.MonotonicDeadline;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class SingleCandidateChatClient implements ChatClient {

    private final ModelCatalogSnapshot catalog;
    private final ModelDeployment deployment;
    private final ModelProvider modelProvider;
    private final ChatProvider provider;
    private final DiagnosticRecorder diagnostics;

    public SingleCandidateChatClient(
            ModelCatalogSnapshot catalog,
            DeploymentId deploymentId,
            ChatProvider provider,
            DiagnosticRecorder diagnostics) {
        this.catalog = Objects.requireNonNull(catalog);
        this.deployment = Objects.requireNonNull(
                catalog.deployments().get(Objects.requireNonNull(deploymentId)),
                "deployment must exist in catalog");
        this.modelProvider = Objects.requireNonNull(
                catalog.providers().get(deployment.providerId()),
                "deployment provider must exist in catalog");
        if (!deployment.capabilities().contains(Capability.CHAT)) {
            throw new IllegalArgumentException("deployment must support CHAT");
        }
        this.provider = Objects.requireNonNull(provider);
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    @Override
    public ChatResult complete(ChatCommand command) {
        Objects.requireNonNull(command);
        UUID logicalCallId = UUID.randomUUID();
        RequestId requestId = requestId(command.options());
        long callStartedNanos = System.nanoTime();
        OffsetDateTime startedAt = OffsetDateTime.now(ZoneOffset.UTC);
        var deadline = MonotonicDeadline.start(
                catalog.runtimePolicy().chat().totalTimeout(),
                command.options().timeout());
        var diagnostic = diagnosticBuilder(logicalCallId, requestId, command);
        diagnostic.addCandidate(candidate(command));
        diagnostic.addRouteEvent(new RouteEventDiagnostic(
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
                deadline.remaining().toMillis(),
                1));

        AtomicBoolean dispatched = new AtomicBoolean();
        AtomicLong attemptStartedOffsetMs = new AtomicLong();
        var context = new ProviderAttemptContext(
                logicalCallId,
                1,
                deadline,
                command.options().cancellation(),
                () -> {
                    if (dispatched.compareAndSet(false, true)) {
                        attemptStartedOffsetMs.set(elapsedMillis(callStartedNanos));
                    }
                });
        com.app.ai.provider.ProviderChatResult providerResult;
        try {
            providerResult = provider.complete(
                    deployment,
                    new ProviderChatRequest(
                            command.messages(),
                            command.tools(),
                            deployment.chat().sampling(command.tier())),
                    context);
        } catch (ProviderFailureException failure) {
            throw completeFailure(
                    failure,
                    logicalCallId,
                    requestId,
                    diagnostic,
                    deadline,
                    dispatched.get(),
                    attemptStartedOffsetMs.get(),
                    callStartedNanos,
                    startedAt);
        } catch (RuntimeException unexpected) {
            throw completeFailure(
                    ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID),
                    logicalCallId,
                    requestId,
                    diagnostic,
                    deadline,
                    dispatched.get(),
                    attemptStartedOffsetMs.get(),
                    callStartedNanos,
                    startedAt);
        }
        if (!dispatched.get() || providerResult == null) {
            throw completeFailure(
                    ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID),
                    logicalCallId,
                    requestId,
                    diagnostic,
                    deadline,
                    dispatched.get(),
                    attemptStartedOffsetMs.get(),
                    callStartedNanos,
                    startedAt);
        }

        long completedOffsetMs = elapsedMillis(callStartedNanos);
        ExecutionTarget target = target();
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
                BreakerState.CLOSED,
                AttemptOutcome.SUCCEEDED,
                null,
                null,
                200,
                providerResult.providerRequestIdMasked(),
                attemptStartedOffsetMs.get(),
                null,
                null,
                null,
                Math.max(0, completedOffsetMs - attemptStartedOffsetMs.get()),
                inputTokens(providerResult.usage()),
                outputTokens(providerResult.usage()),
                null));
        diagnostic.addRouteEvent(new RouteEventDiagnostic(
                4,
                completedOffsetMs,
                RouteEventType.CALL_TERMINATED,
                0,
                1,
                0,
                deployment.providerId(),
                deployment.id(),
                TerminationReason.COMPLETED.name(),
                null,
                deadline.remaining().toMillis(),
                null));

        var execution = new ExecutionInfo(logicalCallId, requestId, target, 1, 0, 0);
        var completed = diagnostic.complete(
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
                new TimingDiagnostic(
                        0L,
                        null,
                        null,
                        null,
                        completedOffsetMs,
                        startedAt,
                        OffsetDateTime.now(ZoneOffset.UTC)));
        recordBestEffort(completed);
        return new ChatResult(
                providerResult.message(),
                providerResult.finishReason(),
                providerResult.usage(),
                execution);
    }

    private ModelCallException completeFailure(
            ProviderFailureException failure,
            UUID logicalCallId,
            RequestId requestId,
            CallDiagnosticBuilder diagnostic,
            com.app.ai.runtime.Deadline deadline,
            boolean dispatched,
            long attemptStartedOffsetMs,
            long callStartedNanos,
            OffsetDateTime startedAt) {
        FailureAction action = FailureActionPolicy.actionFor(failure.failureType());
        TerminationReason termination = FailureActionPolicy.terminationReason(failure.failureType(), false);
        long completedOffsetMs = elapsedMillis(callStartedNanos);
        int attemptCount = dispatched ? 1 : 0;
        if (dispatched) {
            diagnostic.addAttempt(new AttemptDiagnostic(
                    1,
                    0,
                    null,
                    null,
                    0,
                    AttemptKind.INITIAL,
                    2,
                    3,
                    target(),
                    modelProvider.quotaDomain(),
                    BreakerState.CLOSED,
                    null,
                    attemptOutcome(action.errorType()),
                    action.errorType(),
                    action.failurePhase(),
                    failure.httpStatus(),
                    failure.providerRequestIdMasked(),
                    attemptStartedOffsetMs,
                    null,
                    null,
                    null,
                    Math.max(0, completedOffsetMs - attemptStartedOffsetMs),
                    null,
                    null,
                    null));
        }
        diagnostic.addRouteEvent(new RouteEventDiagnostic(
                dispatched ? 4 : 2,
                completedOffsetMs,
                RouteEventType.CALL_TERMINATED,
                0,
                dispatched ? 1 : null,
                0,
                deployment.providerId(),
                deployment.id(),
                termination.name(),
                null,
                deadline.remaining().toMillis(),
                null));
        CallOutcome outcome = action.errorType() == ModelErrorType.CANCELED
                ? CallOutcome.CANCELED
                : CallOutcome.FAILED;
        var completed = diagnostic.complete(
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
                new TimingDiagnostic(
                        0L,
                        null,
                        null,
                        null,
                        completedOffsetMs,
                        startedAt,
                        OffsetDateTime.now(ZoneOffset.UTC)));
        recordBestEffort(completed);

        ExecutionTarget attempted = dispatched ? target() : null;
        var execution = new ExecutionInfo(logicalCallId, requestId, null, attemptCount, 0, 0);
        var terminalCause = new TerminalCause(
                action.errorType(),
                action.failurePhase(),
                termination,
                failure.httpStatus(),
                attempted);
        return new ModelCallException(
                stableMessage(action.errorType()),
                execution,
                terminalCause,
                retryableByCaller(action.errorType()));
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

    private RouteCandidateDiagnostic candidate(ChatCommand command) {
        return new RouteCandidateDiagnostic(
                0,
                target(),
                Capability.CHAT,
                command.tier(),
                modelProvider.quotaDomain(),
                RouteDecision.ELIGIBLE,
                null,
                0,
                deployment.defaultCandidate(),
                deployment.priority(),
                BreakerState.CLOSED);
    }

    private ExecutionTarget target() {
        return new ExecutionTarget(
                deployment.providerId(), deployment.id(), deployment.modelIdentity());
    }

    private void recordBestEffort(com.app.ai.diagnostic.CompletedCallDiagnostic completed) {
        try {
            diagnostics.offer(completed);
        } catch (RuntimeException ignored) {
            // Diagnostics are best effort and must never change the model result.
        }
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

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
