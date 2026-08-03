package com.app.ai.model;

public record ToolDefinition(String name, String description, String inputSchemaJson) {

    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("tool definition name must not be blank");
        }
        inputSchemaJson = JsonTextValidator.requireJsonObject("tool input schema", inputSchemaJson);
    }
}
