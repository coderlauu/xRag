package com.app.ai.diagnostic;

import com.app.ai.model.DeploymentId;
import com.app.ai.model.ProviderId;
import java.util.Objects;

public record RouteEventDiagnostic(
        int sequenceNo,
        long occurredOffsetMs,
        RouteEventType eventType,
        Integer workUnitIndex,
        Integer attemptNo,
        Integer candidateOrder,
        ProviderId providerId,
        DeploymentId deploymentId,
        String reason,
        Long waitDurationMs,
        Long remainingBudgetMs,
        Integer eligibleCandidateCount) {

    public RouteEventDiagnostic {
        if (sequenceNo <= 0 || occurredOffsetMs < 0) {
            throw new IllegalArgumentException("route event sequence and offset must be non-negative");
        }
        Objects.requireNonNull(eventType);
    }
}
