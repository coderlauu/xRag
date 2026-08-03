package com.app.ai.runtime;

import java.time.Duration;
import java.util.Objects;

public final class MonotonicDeadline implements Deadline {

    private final NanoTicker ticker;
    private final long startedNanos;
    private final long allowedNanos;
    private long lastRemainingNanos;

    private MonotonicDeadline(Duration allowed, NanoTicker ticker) {
        this.ticker = Objects.requireNonNull(ticker);
        this.allowedNanos = positiveNanos(allowed, "allowed");
        this.lastRemainingNanos = allowedNanos;
        this.startedNanos = ticker.read();
    }

    public static MonotonicDeadline start(
            Duration moduleLimit, Duration callerTimeout, NanoTicker ticker) {
        Objects.requireNonNull(moduleLimit);
        Duration allowed = callerTimeout == null || moduleLimit.compareTo(callerTimeout) <= 0
                ? moduleLimit
                : callerTimeout;
        return new MonotonicDeadline(allowed, ticker);
    }

    public static MonotonicDeadline start(Duration moduleLimit, Duration callerTimeout) {
        return start(moduleLimit, callerTimeout, NanoTicker.system());
    }

    @Override
    public synchronized Duration remaining() {
        long elapsed = ticker.read() - startedNanos;
        long calculated = elapsed <= 0 ? allowedNanos : Math.max(0, allowedNanos - elapsed);
        lastRemainingNanos = Math.min(lastRemainingNanos, calculated);
        return Duration.ofNanos(lastRemainingNanos);
    }

    private static long positiveNanos(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration.toNanos();
    }
}
