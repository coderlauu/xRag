package com.app.ai.provider;

import com.app.ai.model.ChatMessage;
import com.app.ai.model.ToolDefinition;
import java.util.List;

public record ProviderChatRequest(List<ChatMessage> messages, List<ToolDefinition> tools) {

    public ProviderChatRequest {
        messages = List.copyOf(messages);
        tools = List.copyOf(tools);
    }
}
