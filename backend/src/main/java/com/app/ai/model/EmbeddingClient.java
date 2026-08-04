package com.app.ai.model;

public interface EmbeddingClient {

    EmbeddingResult embed(EmbeddingCommand command);
}
