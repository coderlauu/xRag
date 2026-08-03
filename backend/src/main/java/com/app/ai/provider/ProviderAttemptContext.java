package com.app.ai.provider;

import com.app.ai.model.CancellationToken;
import com.app.ai.runtime.Deadline;
import java.util.Objects;
import java.util.UUID;

public record ProviderAttemptContext(
        UUID logicalCallId,
        int attemptNo,
        Deadline deadline,
        CancellationToken cancellation,
        AttemptLifecycle lifecycle) {

    public ProviderAttemptContext(
            UUID logicalCallId,
            int attemptNo,
            Deadline deadline,
            CancellationToken cancellation) {
        this(logicalCallId, attemptNo, deadline, cancellation, AttemptLifecycle.noop());
    }

    public ProviderAttemptContext {
        Objects.requireNonNull(logicalCallId);
        if (attemptNo <= 0) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        Objects.requireNonNull(deadline);
        Objects.requireNonNull(cancellation);
        Objects.requireNonNull(lifecycle);
    }
}
