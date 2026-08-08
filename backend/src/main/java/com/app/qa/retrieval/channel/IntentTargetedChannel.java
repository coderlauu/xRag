package com.app.qa.retrieval.channel;

import com.app.knowledge.search.ChunkSearchPort;
import com.app.qa.retrieval.ChannelResult;
import com.app.qa.retrieval.RetrievalScope;
import com.app.qa.retrieval.SearchChannel;
import com.app.qa.retrieval.SearchChannelType;
import org.springframework.stereotype.Component;

/** 定向向量通道：3A 给出 KB_TARGETED 时激活，限定在动作里的知识库集合。 */
@Component
public class IntentTargetedChannel implements SearchChannel {

    private final ChunkSearchPort search;
    private final QueryVectorSupplier vectors;

    public IntentTargetedChannel(ChunkSearchPort search, QueryVectorSupplier vectors) {
        this.search = search;
        this.vectors = vectors;
    }

    @Override
    public SearchChannelType type() {
        return SearchChannelType.INTENT_TARGETED;
    }

    @Override
    public boolean isActive(RetrievalScope scope) {
        return !scope.targetedKbIds().isEmpty();
    }

    @Override
    public ChannelResult search(RetrievalScope scope, int limit) {
        return new ChannelResult(
                type(),
                search.searchByVector(
                        scope.targetedKbIds(), vectors.embed(scope.rewrittenQuestion()), limit));
    }
}
