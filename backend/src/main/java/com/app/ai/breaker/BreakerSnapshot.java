package com.app.ai.breaker;

import com.app.ai.diagnostic.BreakerState;
import com.app.ai.model.ExecutionTarget;
import java.time.OffsetDateTime;
import java.util.Objects;

public record BreakerSnapshot(
        BreakerKey key,
        ExecutionTarget target,
        BreakerState state,
        BreakerRestartSemantics restartSemantics,
        boolean allowsCall,
        boolean halfOpenPermissionInUse,
        OffsetDateTime lastChangedAt,
        BreakerTransitionReason lastChangeReason,
        float failureRate,
        float slowCallRate,
        int bufferedCalls,
        int successfulCalls,
        int failedCalls,
        int slowCalls,
        long rejectedCalls,
        long rateLimitedCalls) {

    public BreakerSnapshot {
        Objects.requireNonNull(key);
        Objects.requireNonNull(target);
        Objects.requireNonNull(state);
        Objects.requireNonNull(restartSemantics);
    }
}
