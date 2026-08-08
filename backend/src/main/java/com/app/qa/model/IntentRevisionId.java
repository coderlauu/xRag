package com.app.qa.model;

import java.util.Objects;
import java.util.UUID;

public record IntentRevisionId(UUID value) {
    public IntentRevisionId {
        Objects.requireNonNull(value, "intentRevisionId");
    }
}
