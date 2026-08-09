package com.app.qa.retrieval;

import com.app.ai.model.RerankDegradationReason;
import java.util.List;

/**
 * 精排之后的候选，以及**精排到底有没有发生**。
 *
 * <p>不变量与 {@code com.app.ai} 的 {@code RerankResult} 同形：{@code reranked=true}
 * 当且仅当 {@code degradation=null}。同一条规则在端口、这里、以及 {@code qa_turn_retrieval}
 * 的 check 约束上各拦一次——因为"降级了却被当成精排过"一旦流到界面，用户看到的是一份
 * 被摆在"已精排"位置上的结果，而他没有任何办法察觉。
 *
 * @param degradation 降级成因。**只进处理详情与链路记录**，不进用户可见的来源区：
 *                    无 Key、供应商故障、Noop 对用户是同一件事（ui-spec §13.4）
 */
public record RerankedCandidates(
        List<FusedCandidate> candidates, boolean reranked, RerankDegradationReason degradation) {

    public RerankedCandidates {
        candidates = List.copyOf(candidates);
        if (reranked != (degradation == null)) {
            throw new IllegalArgumentException(
                    "reranked must be true exactly when degradation is null");
        }
    }

    public static RerankedCandidates applied(List<FusedCandidate> candidates) {
        return new RerankedCandidates(candidates, true, null);
    }

    public static RerankedCandidates degraded(
            List<FusedCandidate> candidates, RerankDegradationReason reason) {
        return new RerankedCandidates(candidates, false, reason);
    }
}
