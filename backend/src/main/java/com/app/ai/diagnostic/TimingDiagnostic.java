package com.app.ai.diagnostic;

import java.time.OffsetDateTime;
import java.util.Objects;

public record TimingDiagnostic(
        Long routeDurationMs,
        Long firstUpstreamEventMs,
        Long firstMeaningfulChunkMs,
        Long downstreamCommitMs,
        long totalDurationMs,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt) {

    public TimingDiagnostic {
        if (totalDurationMs < 0) {
            throw new IllegalArgumentException("totalDurationMs must be non-negative");
        }
        Objects.requireNonNull(startedAt);
        Objects.requireNonNull(completedAt);
    }
}
