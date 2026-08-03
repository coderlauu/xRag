package com.app.ai.model;

public record ToolResultMessage(String toolCallId, String text) implements ChatMessage {

    public ToolResultMessage {
        if (toolCallId == null || toolCallId.isBlank()) {
            throw new IllegalArgumentException("toolCallId must not be blank");
        }
        if (text == null) {
            throw new IllegalArgumentException("tool result text must not be null");
        }
    }
}
