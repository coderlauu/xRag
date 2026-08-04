package com.app.ai.model;

import java.util.List;
import java.util.Objects;

public record EmbeddingResult(
        List<float[]> vectors,
        ModelIdentity modelIdentity,
        int dimensions,
        EmbeddingExecutionInfo execution) {

    public EmbeddingResult {
        vectors = List.copyOf(vectors);
        Objects.requireNonNull(modelIdentity);
        Objects.requireNonNull(execution);
        if (dimensions <= 0) {
            throw new IllegalArgumentException("dimensions must be positive");
        }
        if (vectors.stream().anyMatch(vector -> vector == null || vector.length != dimensions)) {
            throw new IllegalArgumentException("every vector must match the result dimensions");
        }
    }
}
