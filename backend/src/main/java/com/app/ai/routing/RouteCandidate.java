package com.app.ai.routing;

import com.app.ai.model.ModelDeployment;
import java.util.Objects;

public record RouteCandidate(
        int candidateOrdinal,
        ModelDeployment deployment,
        CandidateDecision decision,
        CandidateRejectionReason rejectionReason,
        Integer plannedOrder,
        BreakerSnapshotState breakerStateSnapshot) {

    public RouteCandidate {
        Objects.requireNonNull(deployment);
        Objects.requireNonNull(decision);
    }
}
