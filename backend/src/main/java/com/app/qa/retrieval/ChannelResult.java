package com.app.qa.retrieval;

import com.app.knowledge.search.ChunkSearchPort.ChunkMatch;
import java.util.List;

/**
 * 单通道的召回结果。<b>顺序即排名</b>——融合只消费排名（ADR 0008 决策 4），
 * 所以这里必须保持通道给出的顺序，任何"顺手再排一次"都会改变融合结果。
 */
public record ChannelResult(SearchChannelType type, List<ChunkMatch> matches) {

    public ChannelResult {
        matches = List.copyOf(matches);
    }

    public static ChannelResult empty(SearchChannelType type) {
        return new ChannelResult(type, List.of());
    }
}
