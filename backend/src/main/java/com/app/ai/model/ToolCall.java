package com.app.ai.model;

public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("tool call id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool call name must not be blank");
        }
        argumentsJson = JsonTextValidator.requireJson("tool call arguments", argumentsJson);
    }
}
