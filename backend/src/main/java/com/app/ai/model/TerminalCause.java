package com.app.ai.model;

import java.util.Objects;

public record TerminalCause(
        ModelErrorType errorType,
        FailurePhase failurePhase,
        TerminationReason terminationReason,
        Integer httpStatus,
        ExecutionTarget attemptedExecution) {

    public TerminalCause {
        Objects.requireNonNull(errorType);
        Objects.requireNonNull(terminationReason);
    }
}
