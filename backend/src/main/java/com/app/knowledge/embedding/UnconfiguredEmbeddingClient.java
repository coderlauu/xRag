package com.app.knowledge.embedding;

import java.util.List;

/**
 * 未配置 {@code app.embedding.api-key} 时注入的实现：应用照常启动，只有真正用到
 * 向量能力时才失败，且失败信息直接说明该配哪个环境变量。
 *
 * <p>沿用 {@code FlywayConfig} / {@code StorageConfig.ensureStorageBucket} 已经建立的
 * 模式——依赖不可用不阻塞启动。少了它，本地只想看看文档列表就得先申请一个按量计费的
 * API Key。
 */
class UnconfiguredEmbeddingClient implements EmbeddingClient {

    private final int dimensions;

    UnconfiguredEmbeddingClient(int dimensions) {
        this.dimensions = dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        throw new EmbeddingException(
                "Embedding 能力未配置：请设置环境变量 EMBEDDING_API_KEY（app.embedding.api-key）后重启应用。"
                        + "未配置时文档可以上传，但无法完成向量化。");
    }

    @Override
    public int dimensions() {
        return dimensions;
    }
}
