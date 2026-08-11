package com.app.qa.retrieval;

import com.app.ai.model.RerankDegradationReason;
import java.util.List;
import java.util.UUID;

/**
 * 一次检索的完整结果。
 *
 * @param partial 部分知识库失败。**由目标状态算出，不由来源条数推断**（PRD §12.4）——
 *                "条数少"可能只是库里本来就少，用它倒推会把正常结果说成不完整
 * @param funnel  漏斗计数。就是课程实现里只存在于 log.info 的那份数据，落成结构化记录
 *                才能被查询和告警（架构 §22.2）
 */
public record RetrievalOutcome(
        UUID turnId,
        RetrievalStatus status,
        boolean partial,
        boolean reranked,
        RerankDegradationReason degradation,
        List<Source> sources,
        List<TargetOutcome> targets,
        Funnel funnel) {

    public RetrievalOutcome {
        sources = List.copyOf(sources);
        targets = List.copyOf(targets);
    }

    /**
     * 一条来源，带归属。
     *
     * <p>归属到具体的子问题与动作，兑现架构 §15 的承诺：3B 结果引用动作，不塞回意图快照表。
     * 同一 chunk 被多个子问题命中时归属第一个——它在融合里排得更靠前。
     */
    public record Source(FusedCandidate candidate, UUID subQuestionId, UUID actionId) {}

    /** 一个检索目标（= 一条 3A 动作）的成败。 */
    public record TargetOutcome(
            UUID actionId, UUID subQuestionId, Long knowledgeBaseId,
            boolean succeeded, RetrievalFailureCode failureCode, long latencyMs) {}

    public record Funnel(int channelHits, int afterDedup, int afterRerank) {}
}
