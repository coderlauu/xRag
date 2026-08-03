package com.app.ai.model;

public record SystemMessage(String text) implements ChatMessage {

    public SystemMessage {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("system message text must not be blank");
        }
    }
}
