package com.app.ai.model;

public record ModelIdentity(String value) implements Comparable<ModelIdentity> {

    public ModelIdentity {
        value = StableIdValidator.require("modelIdentity", value, 160);
    }

    @Override
    public int compareTo(ModelIdentity other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value;
    }
}
