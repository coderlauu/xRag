package com.app.ai.runtime;

import com.app.ai.model.FailurePhase;
import com.app.ai.model.ModelErrorType;
import java.util.Objects;

public record FailureAction(
        ModelErrorType errorType,
        FailurePhase failurePhase,
        RetryDirective sameCandidateRetry,
        SwitchDirective candidateSwitch,
        BreakerSettlement breakerSettlement) {

    public FailureAction {
        Objects.requireNonNull(errorType);
        Objects.requireNonNull(sameCandidateRetry);
        Objects.requireNonNull(candidateSwitch);
        Objects.requireNonNull(breakerSettlement);
    }

    public boolean mayRetrySameCandidate(boolean retryAfterAcceptable) {
        return switch (sameCandidateRetry) {
            case NEVER -> false;
            case ONCE_WITH_FULL_JITTER -> true;
            case ACCEPTABLE_RETRY_AFTER -> retryAfterAcceptable;
        };
    }

    public boolean maySwitchCandidate(boolean committed, boolean independentQuotaDomain) {
        if (committed) {
            return false;
        }
        return switch (candidateSwitch) {
            case NEVER -> false;
            case BEFORE_COMMIT -> true;
            case INDEPENDENT_QUOTA_BEFORE_COMMIT -> independentQuotaDomain;
        };
    }
}
