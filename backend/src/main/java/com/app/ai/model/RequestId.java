package com.app.ai.model;

public record RequestId(String value) {

    public RequestId {
        value = StableIdValidator.require("requestId", value, 128);
    }
}
