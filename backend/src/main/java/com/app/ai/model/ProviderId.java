package com.app.ai.model;

public record ProviderId(String value) implements Comparable<ProviderId> {

    public ProviderId {
        value = StableIdValidator.require("providerId", value, 96);
    }

    @Override
    public int compareTo(ProviderId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
