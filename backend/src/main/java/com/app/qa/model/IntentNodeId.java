package com.app.qa.model;

import java.util.Objects;
import java.util.UUID;

public record IntentNodeId(UUID value) {
    public IntentNodeId {
        Objects.requireNonNull(value, "intentNodeId");
    }
}
