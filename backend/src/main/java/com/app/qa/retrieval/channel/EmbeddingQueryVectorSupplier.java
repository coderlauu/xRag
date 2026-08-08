package com.app.qa.retrieval.channel;

import com.app.ai.model.CallOptions;
import com.app.ai.model.EmbeddingClient;
import com.app.ai.model.EmbeddingCommand;
import com.app.ai.model.ModelIdentity;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 用 {@code com.app.ai} 的 Embedding 能力端口把查询文本转成向量。
 *
 * <p><b>已知限制，写在这里而不是留给下一个人踩</b>：查询向量用的是
 * {@code app.knowledge.default-embedding-identity}，而 {@code knowledge_base.embedding_model}
 * 是**按知识库**记录的。两者不一致时，向量距离就是在比较两个模型的输出，没有意义。
 *
 * <p>这不是 3B 引入的缺陷——单张 `document_chunk_embedding` 表上建一个 HNSW 索引，本身就
 * 隐含"全库向量同源"的假设，跨身份的全局检索在**存储层**就已经不成立了。3B 只是第一个
 * 真正去读它的人。真正的修法是给检索按 embedding identity 分区或强制全库同源，属 Phase 4
 * 检索质量工作，不在本工单范围内。
 */
@Component
class EmbeddingQueryVectorSupplier implements QueryVectorSupplier {

    private final EmbeddingClient embeddingClient;
    private final String defaultIdentity;

    EmbeddingQueryVectorSupplier(
            EmbeddingClient embeddingClient,
            @Value("${app.knowledge.default-embedding-identity}") String defaultIdentity) {
        this.embeddingClient = embeddingClient;
        this.defaultIdentity = defaultIdentity;
    }

    @Override
    public float[] embed(String question) {
        return embeddingClient
                .embed(new EmbeddingCommand(
                        new ModelIdentity(defaultIdentity), List.of(question), CallOptions.defaults()))
                .vectors()
                .get(0);
    }
}
