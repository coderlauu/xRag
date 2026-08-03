package com.app.ai.diagnostic;

import com.app.ai.model.Capability;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.FailurePhase;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.RerankDegradationReason;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.TerminationReason;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CompletedCallDiagnostic(
        UUID logicalCallId,
        String requestId,
        Capability capability,
        ServiceTier serviceTier,
        ModelIdentity requiredModelIdentity,
        boolean stream,
        String policyVersion,
        String configFingerprint,
        CallOutcome outcome,
        ModelErrorType normalizedErrorType,
        FailurePhase failurePhase,
        TerminationReason terminationReason,
        Integer httpStatus,
        ChatFinishReason finishReason,
        boolean committed,
        Boolean rerankApplied,
        RerankDegradationReason degradedReason,
        UsageDiagnostic usage,
        TimingDiagnostic timing,
        List<RouteCandidateDiagnostic> candidates,
        List<RouteEventDiagnostic> routeEvents,
        List<AttemptDiagnostic> attempts,
        List<EmbeddingBatchExecutionDiagnostic> embeddingBatches) {

    public CompletedCallDiagnostic {
        Objects.requireNonNull(logicalCallId);
        Objects.requireNonNull(requestId);
        Objects.requireNonNull(capability);
        Objects.requireNonNull(policyVersion);
        Objects.requireNonNull(configFingerprint);
        Objects.requireNonNull(outcome);
        Objects.requireNonNull(terminationReason);
        Objects.requireNonNull(usage);
        Objects.requireNonNull(timing);
        candidates = List.copyOf(candidates);
        routeEvents = List.copyOf(routeEvents);
        attempts = List.copyOf(attempts);
        embeddingBatches = List.copyOf(embeddingBatches);
        if (stream != (capability == Capability.STREAM_CHAT)) {
            throw new IllegalArgumentException("stream must match STREAM_CHAT capability");
        }
    }

    public int retryCount() {
        return (int) attempts.stream().filter(attempt -> attempt.attemptKind() == AttemptKind.RETRY).count();
    }

    public int failoverCount() {
        return (int) attempts.stream().filter(attempt -> attempt.attemptKind() == AttemptKind.FAILOVER).count();
    }

    public ExecutionSummary executionSummary() {
        if (outcome != CallOutcome.SUCCEEDED && outcome != CallOutcome.STREAM_FAILED) {
            return new ExecutionSummary(null, null, false, 0);
        }
        var contributors = new LinkedHashSet<ExecutionTarget>();
        attempts.stream()
                .filter(attempt -> attempt.outcome() == AttemptOutcome.SUCCEEDED
                        || (outcome == CallOutcome.STREAM_FAILED
                                && attempt.outcome() == AttemptOutcome.STREAM_FAILED))
                .map(AttemptDiagnostic::target)
                .forEach(contributors::add);
        ExecutionTarget actual = contributors.size() == 1 ? contributors.iterator().next() : null;
        String requestIdMasked = contributors.size() == 1
                ? attempts.stream()
                        .filter(attempt -> attempt.target().equals(actual))
                        .map(AttemptDiagnostic::providerRequestIdMasked)
                        .filter(Objects::nonNull)
                        .reduce((first, second) -> second)
                        .orElse(null)
                : null;
        return new ExecutionSummary(actual, requestIdMasked, contributors.size() > 1, contributors.size());
    }

    public record ExecutionSummary(
            ExecutionTarget actualExecution,
            String providerRequestIdMasked,
            boolean mixedExecution,
            int contributingDeploymentCount) {
    }
}
