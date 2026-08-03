package com.app.ai.provider;

import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.TokenUsage;

public sealed interface ProviderChatEvent
        permits ProviderChatEvent.TextDelta,
                ProviderChatEvent.ToolCallDelta,
                ProviderChatEvent.RefusalDelta,
                ProviderChatEvent.Completed {

    record TextDelta(String text) implements ProviderChatEvent {
    }

    record ToolCallDelta(String callId, int index, String name, String argumentsFragment)
            implements ProviderChatEvent {
    }

    record RefusalDelta(String text) implements ProviderChatEvent {
    }

    record Completed(ChatFinishReason finishReason, TokenUsage usage) implements ProviderChatEvent {
    }
}
