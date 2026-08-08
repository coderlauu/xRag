package com.app.qa.retrieval.channel;

import com.app.knowledge.search.ChunkSearchPort;
import com.app.qa.retrieval.ChannelResult;
import com.app.qa.retrieval.RetrievalScope;
import com.app.qa.retrieval.SearchChannel;
import com.app.qa.retrieval.SearchChannelType;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 全局向量通道：3A 给出 GLOBAL_FALLBACK 时激活，不带 kb 过滤。
 *
 * <p><b>与定向通道互斥</b>（ADR 0008 决策 2）。在 pgvector 单表下定向结果是全局结果的子集，
 * 两个都跑等于查两遍同一份数据、去重后白白多占一条连接。互斥判据不是这里自己发明的，
 * 而是 3A 的动作模型已经定好的：GLOBAL_FALLBACK 只在"无候选 / 最高分 &lt;0.6 / 单候选 &lt;0.8"时产生。
 */
@Component
public class GlobalVectorChannel implements SearchChannel {

    private final ChunkSearchPort search;
    private final QueryVectorSupplier vectors;

    public GlobalVectorChannel(ChunkSearchPort search, QueryVectorSupplier vectors) {
        this.search = search;
        this.vectors = vectors;
    }

    @Override
    public SearchChannelType type() {
        return SearchChannelType.GLOBAL_VECTOR;
    }

    @Override
    public boolean isActive(RetrievalScope scope) {
        return scope.globalFallback() && scope.targetedKbIds().isEmpty();
    }

    @Override
    public ChannelResult search(RetrievalScope scope, int limit) {
        return new ChannelResult(
                type(), search.searchByVector(Set.of(), vectors.embed(scope.rewrittenQuestion()), limit));
    }
}
