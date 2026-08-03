package com.app.ai.breaker;

import com.app.ai.model.Capability;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ProviderId;
import java.util.Objects;

public record BreakerKey(
        ProviderId providerId,
        DeploymentId deploymentId,
        Capability capability) {

    public BreakerKey {
        Objects.requireNonNull(providerId);
        Objects.requireNonNull(deploymentId);
        Objects.requireNonNull(capability);
    }

    public String value() {
        return providerId.value() + "/" + deploymentId.value() + "/" + capability.name();
    }
}
