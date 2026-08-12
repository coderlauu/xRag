package com.app.qa.generation;

public enum GenerationTaskStatus {
    QUEUED(false),
    GENERATING(false),
    COMPLETED(true),
    STOPPED(true),
    FAILED(true),
    TIMED_OUT(true);

    private final boolean terminal;

    GenerationTaskStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
