package com.app.ai.model;

public interface ChatClient {

    ChatResult complete(ChatCommand command);
}
