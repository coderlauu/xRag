package com.app.ai.runtime;

import com.app.ai.model.FailurePhase;
import com.app.ai.model.ModelErrorType;
import java.util.Objects;

public record FailureMapping(ModelErrorType errorType, FailurePhase failurePhase) {

    public FailureMapping {
        Objects.requireNonNull(errorType);
    }
}
