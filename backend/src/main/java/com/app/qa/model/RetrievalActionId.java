package com.app.qa.model;

import java.util.Objects;
import java.util.UUID;

public record RetrievalActionId(UUID value) {
    public RetrievalActionId {
        Objects.requireNonNull(value, "retrievalActionId");
    }
}
