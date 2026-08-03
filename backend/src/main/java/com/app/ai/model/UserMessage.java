package com.app.ai.model;

public record UserMessage(String text) implements ChatMessage {

    public UserMessage {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("user message text must not be blank");
        }
    }
}
