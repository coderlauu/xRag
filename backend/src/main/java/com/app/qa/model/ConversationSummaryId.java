package com.app.qa.model;

import java.util.Objects;
import java.util.UUID;

public record ConversationSummaryId(UUID value) {
    public ConversationSummaryId {
        Objects.requireNonNull(value, "conversationSummaryId");
    }
}
