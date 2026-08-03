package com.app.ai.runtime;

import java.time.Duration;
import java.util.Objects;

public record PhaseTimeout(Duration duration, TimeoutSource source) {

    public PhaseTimeout {
        Objects.requireNonNull(duration);
        Objects.requireNonNull(source);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("duration must be positive");
        }
    }
}
