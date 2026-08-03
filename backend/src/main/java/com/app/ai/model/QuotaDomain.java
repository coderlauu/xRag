package com.app.ai.model;

public record QuotaDomain(String value) {

    public QuotaDomain {
        value = StableIdValidator.require("quotaDomain", value, 128);
    }

    @Override
    public String toString() {
        return value;
    }
}
