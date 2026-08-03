package com.app.ai.runtime;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;

public final class RetryAfterPolicy {

    public static final Duration MAX_WAIT = Duration.ofSeconds(5);

    private RetryAfterPolicy() {
    }

    public static Optional<Duration> accept(
            String rawValue, Clock clock, Deadline deadline, Duration minimumRequestBudget) {
        Objects.requireNonNull(clock);
        Objects.requireNonNull(deadline);
        requirePositive(minimumRequestBudget);
        Optional<Duration> parsed = parse(rawValue, clock);
        if (parsed.isEmpty()) {
            return Optional.empty();
        }
        Duration wait = parsed.get();
        if (wait.compareTo(MAX_WAIT) > 0
                || deadline.remaining().compareTo(wait.plus(minimumRequestBudget)) < 0) {
            return Optional.empty();
        }
        return Optional.of(wait);
    }

    private static Optional<Duration> parse(String rawValue, Clock clock) {
        if (rawValue == null || rawValue.isBlank()) {
            return Optional.empty();
        }
        String value = rawValue.trim();
        try {
            long seconds = Long.parseLong(value);
            return seconds < 0 ? Optional.empty() : Optional.of(Duration.ofSeconds(seconds));
        } catch (NumberFormatException ignored) {
            try {
                Duration until = Duration.between(
                        clock.instant(),
                        ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
                return until.isNegative() ? Optional.empty() : Optional.of(until);
            } catch (DateTimeParseException invalidDate) {
                return Optional.empty();
            }
        }
    }

    private static void requirePositive(Duration duration) {
        Objects.requireNonNull(duration);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("minimumRequestBudget must be positive");
        }
    }
}
