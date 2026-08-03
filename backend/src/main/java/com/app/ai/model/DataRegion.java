package com.app.ai.model;

public record DataRegion(String value) {

    public DataRegion {
        value = StableIdValidator.require("dataRegion", value, 96);
    }

    @Override
    public String toString() {
        return value;
    }
}
