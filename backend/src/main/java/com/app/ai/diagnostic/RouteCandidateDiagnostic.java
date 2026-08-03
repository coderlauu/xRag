package com.app.ai.diagnostic;

import com.app.ai.model.Capability;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.QuotaDomain;
import com.app.ai.model.ServiceTier;
import java.util.Objects;

public record RouteCandidateDiagnostic(
        int candidateOrdinal,
        ExecutionTarget target,
        Capability capability,
        ServiceTier serviceTier,
        QuotaDomain quotaDomain,
        RouteDecision decision,
        RouteRejectionReason rejectionReason,
        Integer plannedOrder,
        boolean defaultCandidate,
        int priority,
        BreakerState breakerStateSnapshot) {

    public RouteCandidateDiagnostic {
        Objects.requireNonNull(target);
        Objects.requireNonNull(capability);
        Objects.requireNonNull(quotaDomain);
        Objects.requireNonNull(decision);
        boolean eligible = decision == RouteDecision.ELIGIBLE;
        if (candidateOrdinal < 0 || priority < 0
                || eligible != (rejectionReason == null && plannedOrder != null)) {
            throw new IllegalArgumentException("route candidate decision fields are inconsistent");
        }
    }
}
