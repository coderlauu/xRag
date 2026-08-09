package com.app.qa.retrieval;

import java.util.List;

/**
 * 融合去重后的一条候选。
 *
 * @param chunkId       稳定身份，也是去重键
 * @param originChannel 来源归属，取命中通道里 {@code priority} 最小的那个
 * @param hitChannels   命中它的全部通道，按 priority 升序。<b>只进"处理详情"，不进用户可见的
 *                      来源区</b>——PRD §12.2 明确"不因为命中两次就显得更可信"
 * @param rrfScore      RRF 累加分。<b>不落库、不进 API</b>，只在本次融合内部决定顺序
 */
public record FusedCandidate(
        long chunkId,
        long kbId,
        long docId,
        SearchChannelType originChannel,
        List<SearchChannelType> hitChannels,
        double rrfScore) {

    public FusedCandidate {
        hitChannels = List.copyOf(hitChannels);
    }
}
