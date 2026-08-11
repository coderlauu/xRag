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
 * <p><b>激活条件只看动作，不看有没有定向</b>。ADR 0008 决策 2 起初写了"两个向量通道不同时激活"，
 * 理由是 pgvector 单表下定向 ⊂ 全局、两个都跑是重复劳动。实现工单 08 时发现那条与 3A 的动作模型
 * 冲突：架构 §9.4 规定"只有一个 KB 候选且分数 &lt;0.8"时会**同时**产出 KB_TARGETED 与
 * GLOBAL_FALLBACK——那正是意图层在说"这个定向匹配不够有把握，再全局找一遍"。压掉全局通道等于
 * 让这个补充从不发生，还会把一个从未执行的目标记成成功。
 *
 * <p>"别重复查"这件事由动作模型本身保证：只有定向动作时 3A 不会产出 GLOBAL_FALLBACK，
 * 本通道也就不激活，不需要再加一道守卫。
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
        return scope.globalFallback();
    }

    @Override
    public ChannelResult search(RetrievalScope scope, int limit) {
        return new ChannelResult(
                type(), search.searchByVector(Set.of(), vectors.embed(scope.rewrittenQuestion()), limit));
    }
}
