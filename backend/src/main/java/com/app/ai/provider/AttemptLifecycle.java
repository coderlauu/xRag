package com.app.ai.provider;

@FunctionalInterface
public interface AttemptLifecycle {

    void dispatched();

    default void connected() {
    }

    default void responseHeadersReceived() {
    }

    static AttemptLifecycle noop() {
        return () -> { };
    }
}
