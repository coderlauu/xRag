package com.app.ai.model;

public record TokenUsage(long inputTokens, long outputTokens, long totalTokens) {

    public TokenUsage {
        if (inputTokens < 0 || outputTokens < 0 || totalTokens != inputTokens + outputTokens) {
            throw new IllegalArgumentException("token usage must be non-negative and internally consistent");
        }
    }
}
