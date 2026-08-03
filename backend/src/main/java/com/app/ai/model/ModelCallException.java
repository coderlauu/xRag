package com.app.ai.model;

import java.util.Objects;

public final class ModelCallException extends RuntimeException {

    private final ExecutionInfo execution;
    private final TerminalCause terminalCause;
    private final boolean retryableByCaller;

    public ModelCallException(
            String message,
            ExecutionInfo execution,
            TerminalCause terminalCause,
            boolean retryableByCaller) {
        super(Objects.requireNonNull(message));
        this.execution = Objects.requireNonNull(execution);
        this.terminalCause = Objects.requireNonNull(terminalCause);
        this.retryableByCaller = retryableByCaller;
    }

    public ExecutionInfo execution() {
        return execution;
    }

    public TerminalCause terminalCause() {
        return terminalCause;
    }

    public boolean retryableByCaller() {
        return retryableByCaller;
    }
}
