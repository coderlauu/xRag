package com.app.qa.retrieval;

import com.app.qa.config.QaRetrievalProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * RRF 融合 + 按 {@code chunkId} 去重（ADR 0008 决策 4）。
 *
 * <p><b>只消费排名，不消费分数。</b>这不是实现偏好，而是产品语义的直接结果：余弦距离与
 * {@code ts_rank_cd} 量纲不同，把它们归一到一起需要一组没有原则性取法的参数，而那组参数会
 * 直接决定用户看到的来源顺序。用排名就不需要它们——这也是 PRD §12.2「不展示任何原始分数」
 * 能够成立的原因。
 *
 * <p>{@code score(c) = Σ 1/(k + rank_i(c))}，rank 从 1 起，{@code k} 默认 60。
 *
 * <p>本类是纯函数：不触数据库、不调模型。融合的确定性因此可以被直接证明，
 * 而不是"跑一遍看看结果像不像"。
 */
@Component
public class ReciprocalRankFusion {

    private final int k;

    @Autowired
    public ReciprocalRankFusion(QaRetrievalProperties properties) {
        this(properties.getRrfK());
    }

    /** 直接给 k 的构造器只给测试用——生产一律走配置，避免出现第二个事实来源。 */
    ReciprocalRankFusion(int k) {
        this.k = k;
    }

    /**
     * 融合多个通道的结果。
     *
     * <p>输出顺序：RRF 分降序；<b>完全并列时按 chunkId 升序</b>。后者不是可有可无的——
     * 没有稳定 tie-break，同一份输入两次运行可能给出不同顺序，"来源顺序即相关性顺序"这个
     * 对用户的承诺就成了随机数。
     */
    public List<FusedCandidate> fuse(List<ChannelResult> results) {
        Map<Long, Accumulator> byChunk = new LinkedHashMap<>();
        for (ChannelResult result : results) {
            int rank = 1;
            for (var match : result.matches()) {
                byChunk.computeIfAbsent(
                                match.chunkId(),
                                id -> new Accumulator(id, match.kbId(), match.docId()))
                        .add(result.type(), 1.0 / (k + rank));
                rank++;
            }
        }
        var fused = new ArrayList<FusedCandidate>(byChunk.size());
        for (Accumulator accumulator : byChunk.values()) {
            fused.add(accumulator.toCandidate());
        }
        fused.sort(Comparator.comparingDouble(FusedCandidate::rrfScore)
                .reversed()
                .thenComparingLong(FusedCandidate::chunkId));
        return List.copyOf(fused);
    }

    /**
     * 截断。两次调用的性质不同：第一次限制喂给 Rerank 的输入量，第二次决定最终来源条数。
     * 分成两次而不是一个参数，是因为它们各自受不同的上限约束（Provider 的 maxDocuments
     * 与 3C 的 Prompt 预算）。
     */
    public List<FusedCandidate> truncate(List<FusedCandidate> candidates, int limit) {
        if (limit < 0) {
            throw new IllegalArgumentException("truncate limit must not be negative");
        }
        return candidates.size() <= limit ? candidates : List.copyOf(candidates.subList(0, limit));
    }

    private static final class Accumulator {
        private final long chunkId;
        private final long kbId;
        private final long docId;
        private final List<SearchChannelType> channels = new ArrayList<>();
        private double score;

        private Accumulator(long chunkId, long kbId, long docId) {
            this.chunkId = chunkId;
            this.kbId = kbId;
            this.docId = docId;
        }

        private void add(SearchChannelType type, double contribution) {
            // 同一通道内不应出现重复 chunkId（数据库主键保证），所以这里不做二次判重——
            // 真出现了说明上游坏了，让分数异常暴露出来比悄悄吞掉更好。
            channels.add(type);
            score += contribution;
        }

        private FusedCandidate toCandidate() {
            var sorted = new ArrayList<>(channels);
            sorted.sort(Comparator.comparingInt(SearchChannelType::priority));
            return new FusedCandidate(chunkId, kbId, docId, sorted.get(0), sorted, score);
        }
    }
}
