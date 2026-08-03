package com.app.ai.runtime;

import java.time.Duration;
import java.util.Objects;

public final class DeadlineWaiter {

    private DeadlineWaiter() {
    }

    public static WaitOutcome waitFor(
            Duration wait,
            Deadline deadline,
            Duration minimumRequestBudget,
            Sleeper sleeper) {
        requireNonNegative(wait, "wait");
        Objects.requireNonNull(deadline);
        requirePositive(minimumRequestBudget, "minimumRequestBudget");
        Objects.requireNonNull(sleeper);
        if (Thread.currentThread().isInterrupted()) {
            return WaitOutcome.INTERRUPTED;
        }
        if (deadline.remaining().compareTo(wait.plus(minimumRequestBudget)) < 0) {
            return WaitOutcome.BUDGET_INSUFFICIENT;
        }
        try {
            sleeper.sleep(wait);
            return WaitOutcome.COMPLETED;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return WaitOutcome.INTERRUPTED;
        }
    }

    private static void requireNonNegative(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
    }

    private static void requirePositive(Duration duration, String name) {
        requireNonNegative(duration, name);
        if (duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
