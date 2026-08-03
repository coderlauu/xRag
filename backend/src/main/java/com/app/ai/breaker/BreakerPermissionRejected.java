package com.app.ai.breaker;

import com.app.ai.diagnostic.BreakerState;
import java.time.OffsetDateTime;
import java.util.Objects;

public record BreakerPermissionRejected(
        BreakerKey key,
        BreakerState state,
        OffsetDateTime occurredAt) implements BreakerAdmission {

    public BreakerPermissionRejected {
        Objects.requireNonNull(key);
        Objects.requireNonNull(state);
        Objects.requireNonNull(occurredAt);
    }
}
