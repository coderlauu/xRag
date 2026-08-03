package com.app.ai.breaker;

import com.app.ai.diagnostic.BreakerState;
import com.app.ai.diagnostic.BreakerTransitionDiagnostic;
import com.app.ai.diagnostic.BreakerTransitionRecorder;
import com.app.ai.model.AiRuntimePolicy;
import com.app.ai.model.Capability;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.ModelCatalogSnapshot;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class Resilience4jModelBreakerRegistry implements ModelBreakerRegistry {

    private final AiRuntimePolicy.BreakerLimits limits;
    private final Clock clock;
    private final BreakerTransitionRecorder transitions;
    private final ConcurrentMap<BreakerKey, Entry> entries = new ConcurrentHashMap<>();

    @Autowired
    public Resilience4jModelBreakerRegistry(
            ModelCatalogSnapshot catalog, BreakerTransitionRecorder transitions) {
        this(catalog.runtimePolicy().breaker(), Clock.systemUTC(), transitions);
    }

    public Resilience4jModelBreakerRegistry(
            AiRuntimePolicy.BreakerLimits limits,
            Clock clock,
            BreakerTransitionRecorder transitions) {
        this.limits = Objects.requireNonNull(limits);
        this.clock = Objects.requireNonNull(clock);
        this.transitions = Objects.requireNonNull(transitions);
    }

    @Override
    public BreakerAdmission tryAcquire(ExecutionTarget target, Capability capability) {
        Objects.requireNonNull(target);
        Objects.requireNonNull(capability);
        Entry entry = entry(target, capability);
        entry.transitionIfOpenWaitElapsed(clock.instant(), limits.openWaitDuration());
        boolean acquired = entry.breaker.tryAcquirePermission();
        BreakerState state = state(entry.breaker.getState());
        OffsetDateTime occurredAt = OffsetDateTime.now(clock);
        if (!acquired) {
            return new BreakerPermissionRejected(entry.key, state, occurredAt);
        }
        entry.activePermissions.incrementAndGet();
        return new BreakerPermission(
                entry.key,
                state,
                occurredAt,
                entry.breaker,
                entry.rateLimitedCalls::incrementAndGet,
                entry.activePermissions::decrementAndGet);
    }

    @Override
    public BreakerSnapshot snapshot(ExecutionTarget target, Capability capability) {
        return snapshot(entry(Objects.requireNonNull(target), Objects.requireNonNull(capability)));
    }

    @Override
    public List<BreakerSnapshot> snapshots() {
        return entries.values().stream()
                .map(this::snapshot)
                .sorted(Comparator.comparing(snapshot -> snapshot.key().value()))
                .toList();
    }

    private Entry entry(ExecutionTarget target, Capability capability) {
        BreakerKey key = new BreakerKey(target.providerId(), target.deploymentId(), capability);
        return entries.computeIfAbsent(key, ignored -> createEntry(key, target));
    }

    private Entry createEntry(BreakerKey key, ExecutionTarget target) {
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindow(
                        limits.slidingWindowSize(),
                        limits.minimumNumberOfCalls(),
                        CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .failureRateThreshold((float) limits.failureRateThreshold())
                .slowCallRateThreshold((float) limits.slowCallRateThreshold())
                .slowCallDurationThreshold(slowCallThreshold(key.capability()))
                .waitDurationInOpenState(limits.openWaitDuration())
                .permittedNumberOfCallsInHalfOpenState(limits.halfOpenPermits())
                .maxWaitDurationInHalfOpenState(limits.halfOpenMaxWait())
                .automaticTransitionFromOpenToHalfOpenEnabled(false)
                .clock(clock)
                .build();
        CircuitBreaker breaker = CircuitBreaker.of(key.value(), config);
        Entry entry = new Entry(key, target, breaker);
        breaker.getEventPublisher().onStateTransition(event -> recordTransition(entry, event));
        return entry;
    }

    private BreakerSnapshot snapshot(Entry entry) {
        CircuitBreaker.Metrics metrics = entry.breaker.getMetrics();
        BreakerState state = state(entry.breaker.getState());
        int activePermissions = entry.activePermissions.get();
        boolean halfOpenPermissionInUse = state == BreakerState.HALF_OPEN
                && activePermissions > 0;
        boolean allowsCall = switch (state) {
            case OPEN -> false;
            case HALF_OPEN -> activePermissions < limits.halfOpenPermits();
            case CLOSED, DISABLED -> true;
        };
        return new BreakerSnapshot(
                entry.key,
                entry.target,
                state,
                BreakerRestartSemantics.RESET_TO_CLOSED,
                allowsCall,
                halfOpenPermissionInUse,
                entry.lastChangedAt,
                entry.lastChangeReason,
                metrics.getFailureRate(),
                metrics.getSlowCallRate(),
                metrics.getNumberOfBufferedCalls(),
                metrics.getNumberOfSuccessfulCalls(),
                metrics.getNumberOfFailedCalls(),
                metrics.getNumberOfSlowCalls(),
                metrics.getNumberOfNotPermittedCalls(),
                entry.rateLimitedCalls.get());
    }

    private void recordTransition(
            Entry entry, CircuitBreakerOnStateTransitionEvent event) {
        CircuitBreaker.StateTransition transition = event.getStateTransition();
        if (transition.getToState() == CircuitBreaker.State.OPEN) {
            entry.openedAt = clock.instant();
        }
        BreakerTransitionReason reason = transitionReason(entry, transition);
        OffsetDateTime transitionedAt = OffsetDateTime.now(clock)
                .withOffsetSameInstant(ZoneOffset.UTC);
        entry.lastChangedAt = transitionedAt;
        entry.lastChangeReason = reason;
        var diagnostic = new BreakerTransitionDiagnostic(
                entry.key,
                entry.target,
                state(transition.getFromState()),
                state(transition.getToState()),
                reason,
                transitionedAt);
        try {
            transitions.offer(diagnostic);
        } catch (RuntimeException ignored) {
            // Transition diagnostics are best effort and never alter breaker state.
        }
    }

    private BreakerTransitionReason transitionReason(
            Entry entry, CircuitBreaker.StateTransition transition) {
        return switch (transition) {
            case CLOSED_TO_OPEN -> entry.breaker.getMetrics().getSlowCallRate()
                            >= limits.slowCallRateThreshold()
                    && entry.breaker.getMetrics().getFailureRate() < limits.failureRateThreshold()
                            ? BreakerTransitionReason.SLOW_CALL_RATE
                            : BreakerTransitionReason.FAILURE_RATE;
            case OPEN_TO_HALF_OPEN -> BreakerTransitionReason.WAIT_ELAPSED;
            case HALF_OPEN_TO_CLOSED -> BreakerTransitionReason.HALF_OPEN_SUCCESS;
            case HALF_OPEN_TO_OPEN -> BreakerTransitionReason.HALF_OPEN_FAILURE;
            default -> throw new IllegalStateException("unsupported breaker transition: " + transition);
        };
    }

    private static BreakerState state(CircuitBreaker.State state) {
        return switch (state) {
            case CLOSED -> BreakerState.CLOSED;
            case OPEN -> BreakerState.OPEN;
            case HALF_OPEN -> BreakerState.HALF_OPEN;
            case DISABLED -> BreakerState.DISABLED;
            default -> throw new IllegalStateException("unsupported breaker state: " + state);
        };
    }

    private static Duration slowCallThreshold(Capability capability) {
        return switch (capability) {
            case CHAT -> Duration.ofSeconds(15);
            case STREAM_CHAT, EMBEDDING -> Duration.ofSeconds(20);
            case RERANK -> Duration.ofSeconds(10);
        };
    }

    private static final class Entry {

        private final BreakerKey key;
        private final ExecutionTarget target;
        private final CircuitBreaker breaker;
        private final AtomicInteger activePermissions = new AtomicInteger();
        private final AtomicLong rateLimitedCalls = new AtomicLong();
        private volatile Instant openedAt;
        private volatile OffsetDateTime lastChangedAt;
        private volatile BreakerTransitionReason lastChangeReason;

        private Entry(BreakerKey key, ExecutionTarget target, CircuitBreaker breaker) {
            this.key = key;
            this.target = target;
            this.breaker = breaker;
        }

        private synchronized void transitionIfOpenWaitElapsed(
                Instant now, Duration openWaitDuration) {
            if (breaker.getState() == CircuitBreaker.State.OPEN
                    && openedAt != null
                    && !now.isBefore(openedAt.plus(openWaitDuration))) {
                breaker.transitionToHalfOpenState();
            }
        }
    }
}
