package com.app.ai.diagnostic;

import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.FailurePhase;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.QuotaDomain;
import java.util.Objects;

public record AttemptDiagnostic(
        int attemptNo,
        int workUnitIndex,
        Integer inputFromInclusive,
        Integer inputToExclusive,
        int candidateOrder,
        AttemptKind attemptKind,
        int startedSequenceNo,
        int completedSequenceNo,
        ExecutionTarget target,
        QuotaDomain quotaDomain,
        BreakerState breakerStateBefore,
        BreakerState breakerStateAfter,
        AttemptOutcome outcome,
        ModelErrorType normalizedErrorType,
        FailurePhase failurePhase,
        Integer httpStatus,
        String providerRequestIdMasked,
        long startedOffsetMs,
        Long firstUpstreamEventMs,
        Long firstMeaningfulChunkMs,
        Long downstreamCommitMs,
        long durationMs,
        Integer inputTokenCount,
        Integer outputTokenCount,
        Integer streamEventCount) {

    public AttemptDiagnostic {
        if (attemptNo <= 0 || workUnitIndex < 0 || candidateOrder < 0
                || startedSequenceNo <= 0 || completedSequenceNo <= startedSequenceNo
                || startedOffsetMs < 0 || durationMs < 0) {
            throw new IllegalArgumentException("attempt sequence, index, and duration fields are inconsistent");
        }
        Objects.requireNonNull(attemptKind);
        Objects.requireNonNull(target);
        Objects.requireNonNull(quotaDomain);
        Objects.requireNonNull(breakerStateBefore);
        Objects.requireNonNull(outcome);
    }
}
