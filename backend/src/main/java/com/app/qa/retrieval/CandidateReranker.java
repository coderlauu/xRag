package com.app.qa.retrieval;

import com.app.ai.model.CallOptions;
import com.app.ai.model.RerankCandidate;
import com.app.ai.model.RerankClient;
import com.app.ai.model.RerankCommand;
import com.app.ai.model.RerankDegradationReason;
import com.app.ai.model.ServiceTier;
import com.app.qa.config.QaRetrievalProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 精排调用与三态传导（ADR 0008 决策 6、架构 §19.5）。
 *
 * <p>三条不能松的规则：
 * <ol>
 *   <li><b>三态原样传导</b>，不在问答侧重新发明。端口已经把"降级"做成了构造期不变量，
 *       这里只负责别把它丢掉。</li>
 *   <li><b>返回不足 topN 不补齐</b>（PRD §12.5）。补进来的正是 Rerank 判定为不相关的片段，
 *       却会被摆在"已精排"的位置上——这比结果少几条有害得多。</li>
 *   <li><b>按稳定 ID 对齐，不按下标</b>。端口返回 {@code originalIndex} 与 {@code id} 两者，
 *       用 id：一旦哪天在调用前多加一道过滤，下标对齐会静默错位而没有任何报错。</li>
 * </ol>
 *
 * <p>精排是**可选增强**：失败降级继续，不让本轮检索失败。这与去重不同——去重是必需清洗，
 * 失败即失败（架构 §19.4）。
 */
@Component
public class CandidateReranker {

    private static final Logger LOGGER = LoggerFactory.getLogger(CandidateReranker.class);

    private final RerankClient rerankClient;
    private final QaRetrievalProperties properties;

    public CandidateReranker(RerankClient rerankClient, QaRetrievalProperties properties) {
        this.rerankClient = rerankClient;
        this.properties = properties;
    }

    /**
     * @param chunkText 按 {@code chunkId} 取正文。传函数而不是先把正文塞进候选：正文只在这一步
     *                  需要，融合与落库都不该带着它走。
     */
    public RerankedCandidates rerank(
            String query, List<FusedCandidate> candidates, LongFunction<String> chunkText) {
        if (candidates.isEmpty()) {
            // 没有候选就没有精排可做。这不是降级——把"没东西可排"说成"精排不可用"，
            // 界面上会出现一个毫无来由的"未精排"标记。
            return RerankedCandidates.applied(List.of());
        }

        Map<String, FusedCandidate> byId = new LinkedHashMap<>();
        var payload = new ArrayList<RerankCandidate>(candidates.size());
        for (FusedCandidate candidate : candidates) {
            String id = Long.toString(candidate.chunkId());
            String text = chunkText.apply(candidate.chunkId());
            if (text == null) {
                // 正文取不到（分块刚被删）——跳过它而不是送一个空串去参与排序。
                continue;
            }
            byId.put(id, candidate);
            payload.add(new RerankCandidate(id, text));
        }
        if (payload.isEmpty()) {
            return RerankedCandidates.applied(List.of());
        }

        try {
            var result = rerankClient.rerank(new RerankCommand(
                    query, payload, properties.getTopN(), ServiceTier.BALANCED, CallOptions.defaults()));
            var ordered = new ArrayList<FusedCandidate>(result.items().size());
            for (var item : result.items()) {
                FusedCandidate candidate = byId.get(item.id());
                if (candidate != null) {
                    ordered.add(candidate);
                }
            }
            // 不补齐：ordered 可能短于 topN，那就是实际条数。
            return result.rerankApplied()
                    ? RerankedCandidates.applied(ordered)
                    : RerankedCandidates.degraded(ordered, result.degradationReason());
        } catch (RuntimeException exception) {
            // 精排是可选增强：失败降级继续，不让本轮检索失败。
            // 只记类型不记消息——供应商错误体不进日志，也不进任何用户可见的地方。
            LOGGER.warn("rerank failed, falling back to fusion order: {}", exception.getClass().getName());
            return RerankedCandidates.degraded(
                    truncate(candidates), RerankDegradationReason.RECOVERABLE_CANDIDATES_EXHAUSTED);
        }
    }

    private List<FusedCandidate> truncate(List<FusedCandidate> candidates) {
        int topN = properties.getTopN();
        return candidates.size() <= topN ? candidates : List.copyOf(candidates.subList(0, topN));
    }
}
