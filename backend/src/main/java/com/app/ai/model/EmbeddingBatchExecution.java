package com.app.ai.model;

import java.util.Objects;

public record EmbeddingBatchExecution(
        int workUnitIndex,
        int inputFromInclusive,
        int inputToExclusive,
        ExecutionTarget actualExecution,
        int attemptCount,
        int retryCount,
        int failoverCount) {

    public EmbeddingBatchExecution {
        Objects.requireNonNull(actualExecution);
        if (workUnitIndex < 0 || inputFromInclusive < 0 || inputToExclusive <= inputFromInclusive) {
            throw new IllegalArgumentException("embedding batch range is inconsistent");
        }
        if (attemptCount <= 0 || retryCount < 0 || failoverCount < 0
                || retryCount + failoverCount > attemptCount) {
            throw new IllegalArgumentException("embedding batch attempt counts are inconsistent");
        }
    }
}
