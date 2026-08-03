package com.app.ai.provider;

@FunctionalInterface
public interface AttemptLifecycle {

    void dispatched();

    static AttemptLifecycle noop() {
        return () -> { };
    }
}
