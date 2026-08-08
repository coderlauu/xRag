package com.app.qa.retrieval.channel;

/**
 * 把查询文本变成向量。
 *
 * <p>抽成一个接口而不是让通道直接持有 {@code EmbeddingClient}：两个向量通道共用同一个查询向量，
 * 一次子问题只该算一次。实现落在问答侧（Embedding 是 {@code com.app.ai} 的能力端口），
 * 与切词留在知识库侧刚好相反——两者的一致性要求不同：切词必须与索引期同源，
 * 向量只需要与索引期用同一个模型身份，而那由知识库的 embedding identity 保证。
 */
@FunctionalInterface
public interface QueryVectorSupplier {

    float[] embed(String question);
}
