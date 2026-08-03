package com.app.ai.diagnostic;

import com.app.ai.breaker.BreakerKey;
import com.app.ai.breaker.BreakerTransitionReason;
import com.app.ai.model.ExecutionTarget;
import java.time.OffsetDateTime;
import java.util.Objects;

public record BreakerTransitionDiagnostic(
        BreakerKey key,
        ExecutionTarget target,
        BreakerState fromState,
        BreakerState toState,
        BreakerTransitionReason reason,
        OffsetDateTime transitionedAt) {

    public BreakerTransitionDiagnostic {
        Objects.requireNonNull(key);
        Objects.requireNonNull(target);
        Objects.requireNonNull(fromState);
        Objects.requireNonNull(toState);
        Objects.requireNonNull(reason);
        Objects.requireNonNull(transitionedAt);
        if (fromState == toState) {
            throw new IllegalArgumentException("breaker transition states must differ");
        }
    }
}
