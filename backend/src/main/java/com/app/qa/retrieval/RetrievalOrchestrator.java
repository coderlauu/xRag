package com.app.qa.retrieval;

import com.app.knowledge.search.ChunkTextLookup;
import com.app.qa.config.QaRetrievalProperties;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 把 3A 的动作变成一次真实检索（架构 §19、工单 08）。
 *
 * <pre>
 * 动作 → 按动作激活通道 → 有界并行 → RRF 融合 → 按 chunkId 去重 → 截断 → 精排 → 落库
 * </pre>
 *
 * <p>四条纪律：
 * <ul>
 *   <li><b>只消费动作，不重算分数</b>：通道激活、定向范围、是否兜底全部来自 {@code qa_retrieval_action}。</li>
 *   <li><b>并行度受配置约束</b>：任务粒度是 (子问题 × 通道)，跑在与意图评分分开的执行器上。
 *       每个任务独占一条只读事务连接，这就是"并行度 &lt; 连接池"那条启动校验的来源。</li>
 *   <li><b>部分失败是显式状态</b>：每个目标各自记成败，{@code partial} 由状态算出而不是由条数倒推。</li>
 *   <li><b>检索与精排都在事务外</b>：事务只覆盖最后的落库。</li>
 * </ul>
 */
@Service
public class RetrievalOrchestrator {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetrievalOrchestrator.class);

    private final RetrievalResultRepository repository;
    private final List<SearchChannel> channels;
    private final ReciprocalRankFusion fusion;
    private final CandidateReranker reranker;
    private final ChunkTextLookup chunkText;
    private final QaRetrievalProperties properties;
    private final Executor executor;

    public RetrievalOrchestrator(
            RetrievalResultRepository repository,
            List<SearchChannel> channels,
            ReciprocalRankFusion fusion,
            CandidateReranker reranker,
            ChunkTextLookup chunkText,
            QaRetrievalProperties properties,
            @Qualifier("qaRetrievalExecutor") Executor executor) {
        this.repository = repository;
        this.channels = List.copyOf(channels);
        this.fusion = fusion;
        this.reranker = reranker;
        this.chunkText = chunkText;
        this.properties = properties;
        this.executor = executor;
    }

    public RetrievalOutcome retrieve(UUID turnId) {
        return run(turnId, repository.findActions(turnId), false);
    }

    /**
     * 只重跑失败目标（ui-spec §13.6 / API §11）。
     *
     * <p><b>不新建 turn、不重新评分、不重问歧义</b>——用户已经确认过的方向不能因为一次重试被重问。
     * 成功的目标不再查询：它们的结果在上一次已经落库，本次只补失败的那部分，通过
     * {@link RetrievalResultRepository#merge} 合并进同一轮的来源集合（架构 §19.7）。
     *
     * <p>返回值里的 {@code sources} 是<b>本次重放新增的那部分</b>，而 {@code status} 与
     * {@code partial} 是按整轮全量目标重算的。库里存的来源不带分数（V9 有意为之），无法从库里
     * 重建完整候选，所以整轮来源集合由上层按需从 {@code qa_retrieval_source} 读取。
     */
    public RetrievalOutcome retryFailed(UUID turnId) {
        Set<UUID> failed = new HashSet<>(repository.findFailedActionIds(turnId));
        if (failed.isEmpty()) {
            throw new IllegalStateException("NOTHING_TO_RETRY");
        }
        var all = repository.findActions(turnId);
        return run(turnId, all.stream().filter(a -> failed.contains(a.actionId())).toList(), true);
    }

    private RetrievalOutcome run(
            UUID turnId, List<RetrievalResultRepository.ActionRow> actions, boolean replay) {
        if (actions.isEmpty()) {
            // 该 turn 没有可执行的检索动作（例如全是 SYSTEM 或 NO_MATCH）。
            // 这不是失败，也不是"没结果"——它压根没进过检索。
            var outcome = new RetrievalOutcome(
                    turnId,
                    RetrievalStatus.NO_RESULT,
                    false,
                    true,
                    null,
                    List.of(),
                    List.of(),
                    new RetrievalOutcome.Funnel(0, 0, 0));
            if (replay) {
                // 重放却一条动作都取不回来（例如动作在两次调用之间被逻辑删除）：
                // 什么都不写。走 save 会按 turn_id 清台，把上一次的结果全删掉。
                LOGGER.warn("replay for turn {} resolved to zero actions; nothing written", turnId);
                return outcome;
            }
            repository.save(outcome);
            return outcome;
        }

        var plans = plan(actions);
        var results = executeInParallel(plans);

        // 目标状态：一个通道任务覆盖它作用域内的全部动作，失败则这些动作一起判失败。
        var targets = new ArrayList<RetrievalOutcome.TargetOutcome>();
        var channelResults = new ArrayList<ChannelResult>();
        var attribution = new LinkedHashMap<Long, Attribution>();
        int channelHits = 0;

        for (TaskResult result : results) {
            // **只有向量通道产生目标行。** 一条动作就是一个目标，而关键词通道与向量通道覆盖同一批
            // 动作——两个都记会撞 uq_qa_target。选向量通道是因为它定义了这个目标：架构 §19.6 规定
            // 关键词通道失败不影响向量通道、也不单独提示用户，那它就不该把目标判成失败。
            if (result.plan().channel().type() != SearchChannelType.KEYWORD) {
                for (var action : result.plan().actions()) {
                    targets.add(new RetrievalOutcome.TargetOutcome(
                            action.actionId(),
                            action.subQuestionId(),
                            action.knowledgeBaseId(),
                            result.failure() == null,
                            result.failure(),
                            result.latencyMs()));
                }
            }
            if (result.failure() != null) {
                continue;
            }
            channelResults.add(result.channelResult());
            channelHits += result.channelResult().matches().size();
            for (var match : result.channelResult().matches()) {
                // 同一 chunk 被多个子问题命中时归属第一个——它在融合里本来就排得更靠前。
                attribution.putIfAbsent(
                        match.chunkId(),
                        new Attribution(
                                result.plan().subQuestionId(),
                                result.plan().actions().get(0).actionId()));
            }
        }

        var fused = fusion.fuse(channelResults);
        var truncated = fusion.truncate(fused, properties.getRerankInputSize());
        var reranked = reranker.rerank(primaryQuestion(actions), truncated, chunkText::text);
        var finalCandidates = fusion.truncate(reranked.candidates(), properties.getTopN());

        var sources = new ArrayList<RetrievalOutcome.Source>(finalCandidates.size());
        for (FusedCandidate candidate : finalCandidates) {
            var origin = attribution.get(candidate.chunkId());
            sources.add(new RetrievalOutcome.Source(
                    candidate, origin.subQuestionId(), origin.actionId()));
        }

        boolean anySucceeded = targets.stream().anyMatch(RetrievalOutcome.TargetOutcome::succeeded);
        boolean anyFailed = targets.stream().anyMatch(t -> !t.succeeded());
        RetrievalStatus status;
        if (!anySucceeded) {
            // 全部目标失败：与"查了但没有"是两件事，文案与可做动作都不同。
            status = RetrievalStatus.RETRIEVAL_FAILED;
        } else if (sources.isEmpty()) {
            status = RetrievalStatus.NO_RESULT;
        } else {
            status = RetrievalStatus.RETRIEVED;
        }

        var outcome = new RetrievalOutcome(
                turnId,
                status,
                anySucceeded && anyFailed,
                reranked.reranked(),
                reranked.degradation(),
                sources,
                targets,
                new RetrievalOutcome.Funnel(channelHits, fused.size(), sources.size()));
        if (!replay) {
            repository.save(outcome);
            return outcome;
        }
        // 重放：合并进同一轮，并按全量目标重算 status/partial——上面那两个布尔量只看得见
        // 本批目标，本批全失败不等于整轮全失败。
        var state = repository.merge(outcome);
        return new RetrievalOutcome(
                turnId,
                state.status(),
                state.partial(),
                outcome.reranked(),
                outcome.degradation(),
                sources,
                targets,
                new RetrievalOutcome.Funnel(channelHits, fused.size(), state.afterRerank()));
    }

    /**
     * 把动作行归拢成"每个子问题一个 scope"，再展开成 (子问题 × 激活通道) 的任务。
     *
     * <p>一个子问题的多条 {@code KB_TARGETED} 会合并成一次定向检索（一条 {@code IN} 查询），
     * 所以一个任务可能覆盖多个目标——失败时它们一起判失败，重试时也一起重跑。
     */
    private List<TaskPlan> plan(List<RetrievalResultRepository.ActionRow> actions) {
        Map<UUID, List<RetrievalResultRepository.ActionRow>> bySubQuestion = new LinkedHashMap<>();
        for (var action : actions) {
            bySubQuestion.computeIfAbsent(action.subQuestionId(), id -> new ArrayList<>()).add(action);
        }
        var plans = new ArrayList<TaskPlan>();
        for (var entry : bySubQuestion.entrySet()) {
            var group = entry.getValue();
            var targetedKbIds = new LinkedHashSet<Long>();
            boolean globalFallback = false;
            for (var action : group) {
                if ("KB_TARGETED".equals(action.actionType()) && action.knowledgeBaseId() != null) {
                    targetedKbIds.add(action.knowledgeBaseId());
                } else if ("GLOBAL_FALLBACK".equals(action.actionType())) {
                    globalFallback = true;
                }
            }
            var scope = new RetrievalScope(group.get(0).question(), targetedKbIds, globalFallback);
            for (SearchChannel channel : channels) {
                if (channel.isActive(scope)) {
                    plans.add(new TaskPlan(entry.getKey(), scope, channel, group));
                }
            }
        }
        return plans;
    }

    private List<TaskResult> executeInParallel(List<TaskPlan> plans) {
        var futures = new ArrayList<CompletableFuture<TaskResult>>(plans.size());
        for (TaskPlan plan : plans) {
            futures.add(CompletableFuture.supplyAsync(() -> execute(plan), executor));
        }
        var results = new ArrayList<TaskResult>(plans.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                results.add(futures.get(i).join());
            } catch (CompletionException exception) {
                // join 本身失败（任务外的意外）也要变成一个显式失败的目标，
                // 而不是让这批动作在结果里凭空消失。
                results.add(new TaskResult(
                        plans.get(i),
                        ChannelResult.empty(plans.get(i).channel().type()),
                        RetrievalFailureCode.KNOWLEDGE_BASE_UNAVAILABLE,
                        0));
            }
        }
        return results;
    }

    private TaskResult execute(TaskPlan plan) {
        long startedAt = System.nanoTime();
        try {
            var result = plan.channel().search(plan.scope(), properties.getChannelTopK());
            return new TaskResult(plan, result, null, elapsedMs(startedAt));
        } catch (RuntimeException exception) {
            // 宁可少返回一部分结果，也不让整轮挂掉——但**失败必须是显式的**，
            // 不能像课程实现那样只留在 log.info 的统计里。
            LOGGER.warn(
                    "retrieval channel {} failed: {}",
                    plan.channel().type(),
                    exception.getClass().getName());
            return new TaskResult(
                    plan,
                    ChannelResult.empty(plan.channel().type()),
                    classify(exception),
                    elapsedMs(startedAt));
        }
    }

    private static RetrievalFailureCode classify(RuntimeException exception) {
        String name = exception.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        if (name.contains("timeout")) {
            return RetrievalFailureCode.RETRIEVAL_TIMEOUT;
        }
        return RetrievalFailureCode.KNOWLEDGE_BASE_UNAVAILABLE;
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private static String primaryQuestion(List<RetrievalResultRepository.ActionRow> actions) {
        return actions.get(0).question();
    }

    /** 一条候选来自哪个子问题的哪条动作。 */
    private record Attribution(UUID subQuestionId, UUID actionId) {}

    private record TaskPlan(
            UUID subQuestionId,
            RetrievalScope scope,
            SearchChannel channel,
            List<RetrievalResultRepository.ActionRow> actions) {}

    private record TaskResult(
            TaskPlan plan, ChannelResult channelResult, RetrievalFailureCode failure, long latencyMs) {}
}
