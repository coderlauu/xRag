package com.app.qa.retrieval.channel;

import com.app.knowledge.search.ChunkSearchPort;
import com.app.qa.retrieval.ChannelResult;
import com.app.qa.retrieval.RetrievalScope;
import com.app.qa.retrieval.SearchChannel;
import com.app.qa.retrieval.SearchChannelType;
import org.springframework.stereotype.Component;

/**
 * 关键词通道：始终激活，作用域随同轮向量通道。
 *
 * <p>传的是**原始问题文本**，切词发生在知识库侧——与索引期同一个 Bean，
 * 这条一致性因此是结构性的，不靠调用方记得（{@link ChunkSearchPort}）。
 */
@Component
public class KeywordChannel implements SearchChannel {

    private final ChunkSearchPort search;

    public KeywordChannel(ChunkSearchPort search) {
        this.search = search;
    }

    @Override
    public SearchChannelType type() {
        return SearchChannelType.KEYWORD;
    }

    @Override
    public boolean isActive(RetrievalScope scope) {
        return true;
    }

    @Override
    public ChannelResult search(RetrievalScope scope, int limit) {
        return new ChannelResult(
                type(), search.searchByKeyword(scope.keywordScope(), scope.rewrittenQuestion(), limit));
    }
}
