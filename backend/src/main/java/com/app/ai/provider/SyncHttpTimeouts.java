package com.app.ai.provider;

import java.time.Duration;
import java.util.Objects;

public record SyncHttpTimeouts(
        Duration connectionRequest,
        Duration connect,
        Duration response) {

    public SyncHttpTimeouts {
        requirePositive(connectionRequest, "connectionRequest");
        requirePositive(connect, "connect");
        requirePositive(response, "response");
    }

    public static SyncHttpTimeouts defaults() {
        return new SyncHttpTimeouts(
                Duration.ofSeconds(3),
                Duration.ofSeconds(3),
                Duration.ofSeconds(30));
    }

    SyncHttpTimeouts boundedBy(Duration remaining) {
        requirePositive(remaining, "remaining");
        return new SyncHttpTimeouts(
                min(connectionRequest, remaining),
                min(connect, remaining),
                min(response, remaining));
    }

    private static void requirePositive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }
}
