package com.app.qa.model;

import java.util.Objects;
import java.util.UUID;

public record MessageId(UUID value) {
    public MessageId {
        Objects.requireNonNull(value, "messageId");
    }
}
