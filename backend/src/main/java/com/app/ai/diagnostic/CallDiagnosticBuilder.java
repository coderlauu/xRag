package com.app.ai.diagnostic;

import com.app.ai.model.Capability;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.FailurePhase;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.RerankDegradationReason;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.TerminationReason;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class CallDiagnosticBuilder {

    private final UUID logicalCallId;
    private final String requestId;
    private final Capability capability;
    private final ServiceTier serviceTier;
    private final ModelIdentity requiredModelIdentity;
    private final boolean stream;
    private final String policyVersion;
    private final String configFingerprint;
    private final List<RouteCandidateDiagnostic> candidates = new ArrayList<>();
    private final List<RouteEventDiagnostic> routeEvents = new ArrayList<>();
    private final List<AttemptDiagnostic> attempts = new ArrayList<>();
    private final List<EmbeddingBatchExecutionDiagnostic> embeddingBatches = new ArrayList<>();

    public CallDiagnosticBuilder(
            UUID logicalCallId,
            String requestId,
            Capability capability,
            ServiceTier serviceTier,
            ModelIdentity requiredModelIdentity,
            boolean stream,
            String policyVersion,
            String configFingerprint) {
        this.logicalCallId = Objects.requireNonNull(logicalCallId);
        this.requestId = Objects.requireNonNull(requestId);
        this.capability = Objects.requireNonNull(capability);
        this.serviceTier = serviceTier;
        this.requiredModelIdentity = requiredModelIdentity;
        this.stream = stream;
        this.policyVersion = Objects.requireNonNull(policyVersion);
        this.configFingerprint = Objects.requireNonNull(configFingerprint);
    }

    public CallDiagnosticBuilder addCandidate(RouteCandidateDiagnostic candidate) {
        candidates.add(Objects.requireNonNull(candidate));
        return this;
    }

    public CallDiagnosticBuilder addRouteEvent(RouteEventDiagnostic event) {
        Objects.requireNonNull(event);
        if (!routeEvents.isEmpty()
                && event.sequenceNo() <= routeEvents.get(routeEvents.size() - 1).sequenceNo()) {
            throw new IllegalArgumentException("route events must be added in sequence order");
        }
        routeEvents.add(event);
        return this;
    }

    public CallDiagnosticBuilder addAttempt(AttemptDiagnostic attempt) {
        attempts.add(Objects.requireNonNull(attempt));
        return this;
    }

    public CallDiagnosticBuilder addEmbeddingBatch(EmbeddingBatchExecutionDiagnostic batch) {
        embeddingBatches.add(Objects.requireNonNull(batch));
        return this;
    }

    public CompletedCallDiagnostic complete(
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
            TimingDiagnostic timing) {
        return new CompletedCallDiagnostic(
                logicalCallId,
                requestId,
                capability,
                serviceTier,
                requiredModelIdentity,
                stream,
                policyVersion,
                configFingerprint,
                outcome,
                normalizedErrorType,
                failurePhase,
                terminationReason,
                httpStatus,
                finishReason,
                committed,
                rerankApplied,
                degradedReason,
                usage,
                timing,
                candidates,
                routeEvents,
                attempts,
                embeddingBatches);
    }
}
