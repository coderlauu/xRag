package com.app.qa.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.model.ExecutionInfo;
import com.app.ai.model.RankedCandidate;
import com.app.ai.model.RerankClient;
import com.app.ai.model.RerankCommand;
import com.app.ai.model.RerankDegradationReason;
import com.app.ai.model.RerankResult;
import com.app.qa.config.QaRetrievalProperties;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

/** 3B-RANK-01~07。 */
class CandidateRerankerTests {

    /** 可编程假 Rerank。真实供应商不参与——三态传导与它无关（任务书 §7）。 */
    private static final class FakeRerank implements RerankClient {
        private Function<RerankCommand, RerankResult> behaviour;
        private RerankCommand lastCommand;

        @Override
        public RerankResult rerank(RerankCommand command) {
            lastCommand = command;
            return behaviour.apply(command);
        }
    }

    private final FakeRerank rerankClient = new FakeRerank();
    private final QaRetrievalProperties properties = new QaRetrievalProperties();
    private final CandidateReranker reranker = new CandidateReranker(rerankClient, properties);

    private static FusedCandidate candidate(long chunkId) {
        return new FusedCandidate(
                chunkId, 1L, 1L, SearchChannelType.KEYWORD, List.of(SearchChannelType.KEYWORD), 0.1);
    }

    private static List<FusedCandidate> candidates(long... ids) {
        var list = new ArrayList<FusedCandidate>();
        for (long id : ids) {
            list.add(candidate(id));
        }
        return list;
    }

    private static ExecutionInfo execution() {
        return new ExecutionInfo(
                java.util.UUID.randomUUID(),
                new com.app.ai.model.RequestId("request-test"),
                new com.app.ai.model.ExecutionTarget(
                        new com.app.ai.model.ProviderId("provider-a"),
                        new com.app.ai.model.DeploymentId("deployment-a"),
                        new com.app.ai.model.ModelIdentity("model-a")),
                1,
                0,
                0);
    }

    private static RerankResult applied(List<String> orderedIds) {
        var items = new ArrayList<RankedCandidate>();
        for (int i = 0; i < orderedIds.size(); i++) {
            items.add(new RankedCandidate(orderedIds.get(i), i, 0.9 - i * 0.1));
        }
        return new RerankResult(items, true, null, execution());
    }

    private static RerankResult degraded(List<String> orderedIds, RerankDegradationReason reason) {
        var items = new ArrayList<RankedCandidate>();
        for (int i = 0; i < orderedIds.size(); i++) {
            items.add(new RankedCandidate(orderedIds.get(i), i, null));
        }
        return new RerankResult(items, false, reason, execution());
    }

    @Test
    void rank01_真实精排时标记为已精排且按端口给的顺序重排() {
        rerankClient.behaviour = command -> applied(List.of("30", "10", "20"));

        var result = reranker.rerank("退货政策", candidates(10, 20, 30), id -> "正文" + id);

        assertThat(result.reranked()).isTrue();
        assertThat(result.degradation()).isNull();
        assertThat(result.candidates()).extracting(FusedCandidate::chunkId).containsExactly(30L, 10L, 20L);
    }

    @Test
    void rank02_Noop降级时仍返回来源并带上成因() {
        rerankClient.behaviour =
                command -> degraded(List.of("10", "20"), RerankDegradationReason.CAPABILITY_NOT_DECLARED);

        var result = reranker.rerank("退货政策", candidates(10, 20), id -> "正文");

        assertThat(result.reranked()).isFalse();
        assertThat(result.degradation()).isEqualTo(RerankDegradationReason.CAPABILITY_NOT_DECLARED);
        assertThat(result.candidates()).hasSize(2);
    }

    @Test
    void rank03_目录无可用部署时降级而不是硬失败() {
        rerankClient.behaviour =
                command -> degraded(List.of("10"), RerankDegradationReason.NO_ELIGIBLE_CANDIDATE);

        var result = reranker.rerank("q", candidates(10), id -> "正文");

        assertThat(result.reranked()).isFalse();
        assertThat(result.candidates()).hasSize(1);
    }

    @Test
    void rank04_精排抛异常时降级继续本轮不失败() {
        rerankClient.behaviour = command -> {
            throw new IllegalStateException("provider exploded with host db-internal-7:5432");
        };

        var result = reranker.rerank("q", candidates(10, 20, 30), id -> "正文");

        assertThat(result.reranked()).isFalse();
        assertThat(result.degradation())
                .isEqualTo(RerankDegradationReason.RECOVERABLE_CANDIDATES_EXHAUSTED);
        // 降级后保留融合顺序，本轮照常有来源。
        assertThat(result.candidates()).extracting(FusedCandidate::chunkId).containsExactly(10L, 20L, 30L);
    }

    @Test
    void rank05_返回不足topN时不用原始候选补齐() {
        // 补进来的正是 Rerank 判定为不相关的片段，却会被摆在"已精排"的位置上（PRD §12.5）。
        rerankClient.behaviour = command -> applied(List.of("10", "20"));

        var result = reranker.rerank("q", candidates(10, 20, 30, 40, 50), id -> "正文");

        assertThat(result.reranked()).isTrue();
        assertThat(result.candidates()).hasSize(2);
        assertThat(result.candidates()).extracting(FusedCandidate::chunkId).doesNotContain(30L, 40L, 50L);
    }

    @Test
    void rank06_降级结果不携带任何分数字段() {
        rerankClient.behaviour =
                command -> degraded(List.of("10"), RerankDegradationReason.CAPABILITY_NOT_DECLARED);

        var result = reranker.rerank("q", candidates(10), id -> "正文");

        // FusedCandidate 里只有 rrfScore（融合内部用，不落库不进 API）；端口的
        // relevanceScore 在降级时由构造期不变量保证为 null，这里不做二次搬运。
        assertThat(result.reranked()).isFalse();
        assertThat(result.candidates()).allMatch(c -> c.rrfScore() > 0);
    }

    @Test
    void rank07_用稳定ID对齐输入被过滤后也不错位() {
        // 30 的正文取不到（分块刚被删），它不会进请求；端口只会看到 10 与 20。
        rerankClient.behaviour = command -> applied(List.of("20", "10"));

        var result = reranker.rerank(
                "q", candidates(10, 20, 30), id -> id == 30L ? null : "正文" + id);

        assertThat(rerankClient.lastCommand.candidates())
                .extracting(com.app.ai.model.RerankCandidate::id)
                .containsExactly("10", "20");
        assertThat(result.candidates()).extracting(FusedCandidate::chunkId).containsExactly(20L, 10L);
    }

    @Test
    void 端口返回未知ID时被忽略而不是错位映射() {
        rerankClient.behaviour = command -> applied(List.of("999", "10"));

        var result = reranker.rerank("q", candidates(10), id -> "正文");

        assertThat(result.candidates()).extracting(FusedCandidate::chunkId).containsExactly(10L);
    }

    @Test
    void 没有候选时不调用精排也不产生降级标记() {
        rerankClient.behaviour = command -> {
            throw new AssertionError("空候选不该调用 Rerank");
        };

        var result = reranker.rerank("q", List.of(), id -> "正文");

        // 把"没东西可排"说成"精排不可用"，界面上会出现一个毫无来由的"未精排"。
        assertThat(result.reranked()).isTrue();
        assertThat(result.candidates()).isEmpty();
    }

    @Test
    void 请求带上配置的topN与BALANCED档() {
        rerankClient.behaviour = command -> applied(List.of("10"));

        reranker.rerank("退货政策", candidates(10), id -> "正文");

        assertThat(rerankClient.lastCommand.topN()).isEqualTo(properties.getTopN());
        assertThat(rerankClient.lastCommand.tier()).isEqualTo(com.app.ai.model.ServiceTier.BALANCED);
        assertThat(rerankClient.lastCommand.query()).isEqualTo("退货政策");
    }
}
