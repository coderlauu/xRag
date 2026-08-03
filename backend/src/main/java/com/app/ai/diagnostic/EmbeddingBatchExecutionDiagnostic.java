package com.app.ai.diagnostic;

import com.app.ai.model.ExecutionTarget;

public record EmbeddingBatchExecutionDiagnostic(
        int workUnitIndex,
        int inputFromInclusive,
        int inputToExclusive,
        ExecutionTarget actualExecution,
        int attemptCount,
        int retryCount,
        int failoverCount) {
}
