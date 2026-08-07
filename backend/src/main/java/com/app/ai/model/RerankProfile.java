package com.app.ai.model;

/**
 * Rerank deployment 的调用上限。
 *
 * <p>{@code maxDocuments} 是供应商文档上限，由工单 23 的 Provider 准入登记为真实值。
 * 超过上限的请求不会静默截断——那等于悄悄丢掉候选、还给出一份看起来正常的排序；
 * 超限的 deployment 直接在路由阶段以 {@code CALLER_CONSTRAINT_MISMATCH} 过滤掉。
 */
public record RerankProfile(int maxDocuments) {

    public RerankProfile {
        if (maxDocuments <= 0) {
            throw new IllegalArgumentException("rerank maxDocuments must be positive");
        }
    }
}
