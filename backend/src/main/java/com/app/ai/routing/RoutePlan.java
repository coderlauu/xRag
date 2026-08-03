package com.app.ai.routing;

import com.app.ai.model.Capability;
import java.util.List;

public record RoutePlan(
        String configFingerprint,
        Capability capability,
        List<RouteCandidate> candidates,
        List<RouteCandidate> eligibleCandidates,
        List<WorkUnitRange> workUnits) {

    public RoutePlan {
        candidates = List.copyOf(candidates);
        eligibleCandidates = List.copyOf(eligibleCandidates);
        workUnits = List.copyOf(workUnits);
    }
}
