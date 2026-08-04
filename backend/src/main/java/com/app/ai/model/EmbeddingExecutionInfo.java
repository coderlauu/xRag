package com.app.ai.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record EmbeddingExecutionInfo(
        UUID logicalCallId,
        RequestId requestId,
        int attemptCount,
        int retryCount,
        int failoverCount,
        boolean mixedExecution,
        List<ExecutionTarget> contributingDeployments,
        List<EmbeddingBatchExecution> batches) {

    public EmbeddingExecutionInfo {
        Objects.requireNonNull(logicalCallId);
        Objects.requireNonNull(requestId);
        contributingDeployments = List.copyOf(contributingDeployments);
        batches = List.copyOf(batches);
        if (attemptCount < 0 || retryCount < 0 || failoverCount < 0
                || retryCount + failoverCount > attemptCount) {
            throw new IllegalArgumentException("execution attempt counts are inconsistent");
        }
        if (mixedExecution != contributingDeployments.size() > 1) {
            throw new IllegalArgumentException(
                    "mixedExecution must hold exactly when more than one deployment contributed");
        }
    }
}
