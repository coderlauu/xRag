package com.app.qa.retrieval;

import java.util.Set;

/**
 * 一个子问题的检索范围，由 3A 的动作行推导而来（ADR 0008 决策 2）。
 *
 * @param rewrittenQuestion 检索用的问题文本。用改写后的版本，不用用户原话。
 * @param targetedKbIds     KB_TARGETED 动作里的知识库集合；空集表示该子问题没有定向动作
 * @param globalFallback    该子问题是否有 GLOBAL_FALLBACK 动作
 */
public record RetrievalScope(String rewrittenQuestion, Set<Long> targetedKbIds, boolean globalFallback) {

    public RetrievalScope {
        targetedKbIds = Set.copyOf(targetedKbIds);
        if (rewrittenQuestion == null || rewrittenQuestion.isBlank()) {
            throw new IllegalArgumentException("retrieval scope requires a non-blank question");
        }
    }

    /**
     * 关键词通道的作用域：有定向就跟着定向，否则全局。
     *
     * <p>它不需要自己的激活开关——关键词通道始终激活，只是范围随向量通道走。
     */
    public Set<Long> keywordScope() {
        return targetedKbIds;
    }
}
