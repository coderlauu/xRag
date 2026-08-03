package com.app.ai.breaker;

import com.app.ai.diagnostic.BreakerState;
import com.app.ai.provider.AttemptFailureType;
import com.app.ai.runtime.BreakerSettlement;
import com.app.ai.runtime.FailureActionPolicy;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BreakerPermission implements BreakerAdmission {

    private final BreakerKey key;
    private final BreakerState state;
    private final OffsetDateTime occurredAt;
    private final CircuitBreaker breaker;
    private final Runnable rateLimited;
    private final Runnable settlementCompleted;
    private final AtomicBoolean settled = new AtomicBoolean();

    BreakerPermission(
            BreakerKey key,
            BreakerState state,
            OffsetDateTime occurredAt,
            CircuitBreaker breaker,
            Runnable rateLimited,
            Runnable settlementCompleted) {
        this.key = Objects.requireNonNull(key);
        this.state = Objects.requireNonNull(state);
        this.occurredAt = Objects.requireNonNull(occurredAt);
        this.breaker = Objects.requireNonNull(breaker);
        this.rateLimited = Objects.requireNonNull(rateLimited);
        this.settlementCompleted = Objects.requireNonNull(settlementCompleted);
    }

    @Override
    public BreakerKey key() {
        return key;
    }

    @Override
    public BreakerState state() {
        return state;
    }

    @Override
    public OffsetDateTime occurredAt() {
        return occurredAt;
    }

    public void succeed(Duration duration) {
        long durationNanos = nanos(duration);
        if (settled.compareAndSet(false, true)) {
            try {
                breaker.onSuccess(durationNanos, TimeUnit.NANOSECONDS);
            } finally {
                settlementCompleted.run();
            }
        }
    }

    public void fail(Duration duration, AttemptFailureType failureType) {
        Objects.requireNonNull(failureType);
        long durationNanos = nanos(duration);
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        BreakerSettlement settlement = FailureActionPolicy.actionFor(failureType).breakerSettlement();
        try {
            switch (settlement) {
                case FAILURE, FAILURE_OR_SLOW, ACTIVE_ATTEMPT_FAILURE -> breaker.onError(
                        durationNanos, TimeUnit.NANOSECONDS, new RecordedBreakerFailure());
                case RATE_LIMITED -> {
                    rateLimited.run();
                    breaker.releasePermission();
                }
                case IGNORED, CONFIGURATION_PROBLEM, RELEASE_PERMISSION -> breaker.releasePermission();
            }
        } finally {
            settlementCompleted.run();
        }
    }

    public void release() {
        if (settled.compareAndSet(false, true)) {
            try {
                breaker.releasePermission();
            } finally {
                settlementCompleted.run();
            }
        }
    }

    public boolean settled() {
        return settled.get();
    }

    private static long nanos(Duration duration) {
        Objects.requireNonNull(duration);
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration must not be negative");
        }
        return duration.toNanos();
    }

    private static final class RecordedBreakerFailure extends RuntimeException {

        private RecordedBreakerFailure() {
            super(null, null, false, false);
        }
    }
}
