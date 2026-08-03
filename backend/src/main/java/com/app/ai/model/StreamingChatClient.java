package com.app.ai.model;

import reactor.core.publisher.Flux;

public interface StreamingChatClient {

    Flux<ChatStreamEvent> stream(ChatCommand command);
}
