package com.app.ai.model;

import java.util.List;

public record ChatCommand(
        List<ChatMessage> messages,
        List<ToolDefinition> tools,
        ServiceTier tier,
        CallOptions options) {

    public ChatCommand {
        messages = List.copyOf(messages);
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        tools = tools == null ? List.of() : List.copyOf(tools);
        tier = tier == null ? ServiceTier.BALANCED : tier;
        options = options == null ? CallOptions.defaults() : options;
    }
}
