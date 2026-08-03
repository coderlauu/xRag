package com.app.ai.model;

@FunctionalInterface
public interface CancellationRegistration extends AutoCloseable {

    @Override
    void close();
}
