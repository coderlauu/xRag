package com.app.ai.runtime;

import java.time.Duration;

@FunctionalInterface
public interface Deadline {

    Duration remaining();

    default boolean isExpired() {
        return remaining().isZero();
    }
}
