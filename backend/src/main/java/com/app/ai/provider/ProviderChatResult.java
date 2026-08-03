package com.app.ai.provider;

import com.app.ai.model.AssistantMessage;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.TokenUsage;
import java.util.Objects;

public record ProviderChatResult(
        AssistantMessage message,
        ChatFinishReason finishReason,
        TokenUsage usage,
        String providerRequestIdMasked) {

    public ProviderChatResult {
        Objects.requireNonNull(message);
        Objects.requireNonNull(finishReason);
    }
}
