package com.app.ai.provider;

import java.util.Arrays;

public record IndexedEmbedding(int index, float[] vector) {

    public IndexedEmbedding {
        if (index < 0) {
            throw new IllegalArgumentException("embedding index must be non-negative");
        }
        vector = Arrays.copyOf(vector, vector.length);
    }

    @Override
    public float[] vector() {
        return Arrays.copyOf(vector, vector.length);
    }
}
