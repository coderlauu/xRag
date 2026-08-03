package com.app.ai.model;

public record EmbeddingProfile(
        boolean legacySingleDeployment,
        String artifactVersion,
        int dimensions,
        int maxBatchItems,
        int maxInputTokensPerItem,
        String preprocessingFingerprint) {
}
