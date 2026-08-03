package com.app.ai.runtime;

import java.time.Duration;
import java.util.Objects;

public final class FullJitterBackoff {

    private static final long BASE_MILLIS = 200;
    private static final long MAX_MILLIS = 2_000;

    private FullJitterBackoff() {
    }

    public static Duration delay(int retryOrdinal, JitterSource jitterSource) {
        if (retryOrdinal < 1) {
            throw new IllegalArgumentException("retryOrdinal must be positive");
        }
        Objects.requireNonNull(jitterSource);
        int shift = Math.min(retryOrdinal - 1, 4);
        Duration upperBound = Duration.ofMillis(Math.min(MAX_MILLIS, BASE_MILLIS << shift));
        Duration chosen = Objects.requireNonNull(jitterSource.choose(upperBound));
        if (chosen.isNegative() || chosen.compareTo(upperBound) > 0) {
            throw new IllegalArgumentException("jitter must be between zero and upperBound");
        }
        return chosen;
    }
}
