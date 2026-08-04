package com.app.ai.model;

import java.util.List;
import java.util.Objects;

public record EmbeddingCommand(
        ModelIdentity modelIdentity,
        List<String> texts,
        CallOptions options) {

    public EmbeddingCommand {
        Objects.requireNonNull(modelIdentity);
        texts = List.copyOf(texts);
        if (texts.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("texts must not contain null");
        }
        options = options == null ? CallOptions.defaults() : options;
    }
}
