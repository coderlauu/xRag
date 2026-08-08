package com.app.qa.model;

import java.util.Objects;
import java.util.UUID;

public record ConversationId(UUID value) {
    public ConversationId {
        Objects.requireNonNull(value, "conversationId");
    }
}
