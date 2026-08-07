package com.app.ai.model;

import java.util.Objects;

/**
 * 重排后的候选。{@code originalIndex} 是它在请求 {@code candidates} 里的下标，
 * {@code relevanceScore} 只在真实 Rerank 时存在——Noop 永不伪造分数。
 */
public record RankedCandidate(String id, int originalIndex, Double relevanceScore) {

    public RankedCandidate {
        Objects.requireNonNull(id);
        if (originalIndex < 0) {
            throw new IllegalArgumentException("rerank originalIndex must be non-negative");
        }
    }
}
