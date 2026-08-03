package com.app.ai.provider;

import java.util.List;

public record ProviderEmbeddingResult(
        List<IndexedEmbedding> embeddings,
        String providerRequestIdMasked) {

    public ProviderEmbeddingResult {
        embeddings = List.copyOf(embeddings);
    }
}
