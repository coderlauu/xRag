package com.app.ai.model;

import java.util.List;
import java.util.Objects;

/**
 * Rerank 结果。{@code rerankApplied=true} 当且仅当 {@code degradationReason=null}——
 * 这条不变量在构造期强制，调用方因此永远不需要猜"没有 reason 是不是就代表成功了"。
 *
 * <p>Noop 保持输入顺序截取 top-N，{@code relevanceScore} 全为 null。
 */
public record RerankResult(
        List<RankedCandidate> items,
        boolean rerankApplied,
        RerankDegradationReason degradationReason,
        ExecutionInfo execution) {

    public RerankResult {
        items = List.copyOf(items);
        Objects.requireNonNull(execution);
        if (rerankApplied != (degradationReason == null)) {
            throw new IllegalArgumentException(
                    "rerankApplied must be true exactly when degradationReason is null");
        }
        if (!rerankApplied && items.stream().anyMatch(item -> item.relevanceScore() != null)) {
            throw new IllegalArgumentException("degraded rerank must not carry relevance scores");
        }
    }
}
