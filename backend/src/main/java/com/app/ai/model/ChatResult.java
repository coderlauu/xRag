package com.app.ai.model;

import java.util.Objects;

public record ChatResult(
        AssistantMessage message,
        ChatFinishReason finishReason,
        TokenUsage usage,
        ExecutionInfo execution) {

    public ChatResult {
        Objects.requireNonNull(message);
        Objects.requireNonNull(finishReason);
        Objects.requireNonNull(execution);
        Objects.requireNonNull(execution.actualExecution(), "successful Chat requires actual execution");
    }
}
