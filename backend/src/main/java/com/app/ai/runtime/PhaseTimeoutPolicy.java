package com.app.ai.runtime;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

public final class PhaseTimeoutPolicy {

    private PhaseTimeoutPolicy() {
    }

    public static Optional<PhaseTimeout> select(
            Deadline deadline, Duration phaseLimit, Duration minimumRequestBudget) {
        Objects.requireNonNull(deadline);
        requirePositive(phaseLimit, "phaseLimit");
        requirePositive(minimumRequestBudget, "minimumRequestBudget");
        Duration remaining = deadline.remaining();
        if (remaining.compareTo(minimumRequestBudget) < 0) {
            return Optional.empty();
        }
        if (remaining.compareTo(phaseLimit) <= 0) {
            return Optional.of(new PhaseTimeout(remaining, TimeoutSource.TOTAL_DEADLINE));
        }
        return Optional.of(new PhaseTimeout(phaseLimit, TimeoutSource.PHASE_LIMIT));
    }

    private static void requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
