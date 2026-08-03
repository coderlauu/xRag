package com.app.ai.model;

import java.util.List;

public record AssistantMessage(String text, String refusal, List<ToolCall> toolCalls) implements ChatMessage {

    public AssistantMessage {
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
        if (isBlank(text) && isBlank(refusal) && toolCalls.isEmpty()) {
            throw new IllegalArgumentException("assistant message must contain text, refusal, or a tool call");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
