package com.app.ai.model;

public record DeploymentId(String value) implements Comparable<DeploymentId> {

    public DeploymentId {
        value = StableIdValidator.require("deploymentId", value, 128);
    }

    @Override
    public int compareTo(DeploymentId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
