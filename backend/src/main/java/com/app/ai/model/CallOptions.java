package com.app.ai.model;

import java.time.Duration;
import java.util.Set;

public record CallOptions(
        RequestId requestId,
        Duration timeout,
        CancellationToken cancellation,
        Set<DataRegion> allowedDataRegions) {

    public CallOptions {
        cancellation = cancellation == null ? CancellationToken.none() : cancellation;
        allowedDataRegions = allowedDataRegions == null ? Set.of() : Set.copyOf(allowedDataRegions);
        if (timeout != null && (timeout.isZero() || timeout.isNegative())) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public static CallOptions defaults() {
        return new CallOptions(null, null, CancellationToken.none(), Set.of());
    }
}
