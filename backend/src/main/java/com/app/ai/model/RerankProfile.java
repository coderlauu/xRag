package com.app.ai.model;

import java.util.Objects;

/**
 * Rerank deployment 的能力专属配置。
 *
 * <p>{@code adapter} 是 **deployment 级的协议适配器覆盖**，缺省继承所属 Provider 的
 * {@code adapter}。存在的理由：同一个 Provider 的不同能力可以走不同协议。阿里云百炼就是
 * 这样——Chat 与 Embedding 确实是 OpenAI 兼容的，唯独 Rerank 只有 DashScope 原生端点
 * （`{base}/reranks` 与 `/rerank` 实测均 404），所以 adapter 不能按 Provider 整体切换。
 *
 * <p>取值不在这里校验合法性，与 {@link ModelProvider#adapter()} 保持一致：目录层只认它是
 * 一个标识，认不认得出由装配层决定，未知取值在启动时失败。这样新增适配器不必回头改 model 包。
 */
public record RerankProfile(int maxDocuments, String adapter) {

    public RerankProfile {
        if (maxDocuments <= 0) {
            throw new IllegalArgumentException("rerank maxDocuments must be positive");
        }
        Objects.requireNonNull(adapter, "rerank adapter must not be null");
    }
}
