package com.app.ai.model;

import java.util.Objects;
import java.util.UUID;

public record ExecutionInfo(
        UUID logicalCallId,
        RequestId requestId,
        ExecutionTarget actualExecution,
        int attemptCount,
        int retryCount,
        int failoverCount) {

    public ExecutionInfo {
        Objects.requireNonNull(logicalCallId);
        Objects.requireNonNull(requestId);
        if (attemptCount < 0 || retryCount < 0 || failoverCount < 0
                || retryCount + failoverCount > attemptCount) {
            throw new IllegalArgumentException("execution attempt counts are inconsistent");
        }
    }
}
