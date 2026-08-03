package com.app.ai.model;

import java.util.Objects;

public record ExecutionTarget(
        ProviderId providerId,
        DeploymentId deploymentId,
        ModelIdentity modelIdentity) {

    public ExecutionTarget {
        Objects.requireNonNull(providerId);
        Objects.requireNonNull(deploymentId);
        Objects.requireNonNull(modelIdentity);
    }
}
