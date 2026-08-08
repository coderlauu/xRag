package com.app.qa.model;

import java.util.Objects;
import java.util.UUID;

public record TurnId(UUID value) {
    public TurnId {
        Objects.requireNonNull(value, "turnId");
    }
}
