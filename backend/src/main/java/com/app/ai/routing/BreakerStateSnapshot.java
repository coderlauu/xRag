package com.app.ai.routing;

import com.app.ai.model.Capability;
import com.app.ai.model.DeploymentId;

@FunctionalInterface
public interface BreakerStateSnapshot {

    BreakerSnapshotState stateOf(DeploymentId deploymentId, Capability capability);

    static BreakerStateSnapshot allClosed() {
        return (deploymentId, capability) -> BreakerSnapshotState.CLOSED;
    }
}
