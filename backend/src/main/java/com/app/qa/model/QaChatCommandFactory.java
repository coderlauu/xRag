package com.app.qa.model;

import com.app.ai.model.CallOptions;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatMessage;
import com.app.ai.model.ServiceTier;
import java.util.List;

public final class QaChatCommandFactory {

    private QaChatCommandFactory() {}

    public static ChatCommand balanced(List<ChatMessage> messages) {
        return new ChatCommand(messages, List.of(), ServiceTier.BALANCED, CallOptions.defaults());
    }
}
