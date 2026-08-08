package com.app.ai.provider;

import com.app.ai.model.ChatMessage;
import com.app.ai.model.ChatSamplingParameters;
import com.app.ai.model.ToolDefinition;
import java.util.List;
import java.util.Objects;

public record ProviderChatRequest(
        List<ChatMessage> messages,
        List<ToolDefinition> tools,
        ChatSamplingParameters sampling) {

    public ProviderChatRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
        Objects.requireNonNull(sampling);
    }
}
