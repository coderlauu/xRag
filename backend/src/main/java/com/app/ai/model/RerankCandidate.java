package com.app.ai.model;

public record RerankCandidate(String id, String text) {

    public RerankCandidate {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("rerank candidate id must not be blank");
        }
        if (text == null) {
            throw new IllegalArgumentException("rerank candidate text must not be null");
        }
    }
}
