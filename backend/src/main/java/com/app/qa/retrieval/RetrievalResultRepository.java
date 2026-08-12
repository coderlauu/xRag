package com.app.qa.retrieval;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** 3B 三张结果表的唯一入口（数据模型 §14）。 */
@Repository
public class RetrievalResultRepository {

    /** 3A 留给 3B 的一条动作，加上它所属子问题的文本。 */
    public record ActionRow(
            UUID actionId, UUID subQuestionId, String question, String actionType, Long knowledgeBaseId) {}

    private final JdbcTemplate jdbc;

    public RetrievalResultRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 读取该 turn 的检索动作。
     *
     * <p><b>只读 3A 已经写死的动作行，不读意图分数、不回读当前树</b>（架构 §15）。
     * 检索用的问题取子问题文本——它已经是改写后的版本。
     */
    public List<ActionRow> findActions(UUID turnId) {
        return jdbc.query(
                """
                select a.id, a.subquestion_id, s.text, a.action_type, a.knowledge_base_id
                  from qa_retrieval_action a
                  join qa_subquestion s on s.id = a.subquestion_id
                 where a.turn_id = ? and a.deleted = false and s.deleted = false
                   and a.action_type in ('KB_TARGETED', 'GLOBAL_FALLBACK')
                 order by s.ordinal, a.ordinal
                """,
                (rs, rowNum) -> new ActionRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("subquestion_id", UUID.class),
                        rs.getString("text"),
                        rs.getString("action_type"),
                        rs.getObject("knowledge_base_id", Long.class)),
                turnId);
    }

    /** 重放后基于全量目标重算出来的 turn 级状态。 */
    public record TurnState(RetrievalStatus status, boolean partial, int afterRerank) {}

    /**
     * 落库整次检索结果（首次检索）。
     *
     * <p>事务只覆盖本地写入——检索与精排都已经在事务外跑完了（架构 §19.1 的依赖纪律）。
     * 先删后插使重放幂等：重跑同一个 turn 的**全部**动作不会留下上一次的半套数据。
     *
     * <p><b>重试失败目标不能走这里</b>，用 {@link #merge}：本方法按 {@code turn_id} 清台，
     * 而重试只带回失败那部分的结果，清台会把上一次成功目标的来源和目标行一起删掉。
     */
    @Transactional
    public void save(RetrievalOutcome outcome) {
        UUID turnId = outcome.turnId();
        jdbc.update("delete from qa_retrieval_source where turn_id = ?", turnId);
        jdbc.update("delete from qa_retrieval_target where turn_id = ?", turnId);

        jdbc.update(
                """
                insert into qa_turn_retrieval
                    (turn_id, status, partial, rerank_applied, degradation,
                     channel_hits, after_dedup, after_rerank, completed_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, now())
                on conflict (turn_id) do update
                    set status = excluded.status,
                        partial = excluded.partial,
                        rerank_applied = excluded.rerank_applied,
                        degradation = excluded.degradation,
                        channel_hits = excluded.channel_hits,
                        after_dedup = excluded.after_dedup,
                        after_rerank = excluded.after_rerank,
                        completed_at = now()
                """,
                turnId,
                outcome.status().name(),
                outcome.partial(),
                outcome.reranked(),
                outcome.degradation() == null ? null : outcome.degradation().name(),
                outcome.funnel().channelHits(),
                outcome.funnel().afterDedup(),
                outcome.funnel().afterRerank());

        insertTargets(turnId, outcome.targets());
        insertSources(turnId, outcome.sources(), 0);
    }

    /**
     * 重放合并（架构 §19.7：「重放结果合并进同一 turn 的来源集合，{@code partial} 标记随之重算」）。
     *
     * <p>与 {@link #save} 的差别只在删除范围：<b>只清掉本批动作的旧行</b>，其余目标与来源留在
     * 原处。重试的语义是"补上失败的那部分"，不是"用这部分替换整轮"。
     *
     * <p>两处约束决定了合并的写法：{@code uq_qa_source_chunk} 让 chunk 在一轮内唯一——重放召回
     * 的 chunk 若已被保留的成功目标记下，归属不变、直接跳过；{@code uq_qa_source_ordinal} 让
     * ordinal 在一轮内唯一——新行的序号接在现有最大值之后。
     *
     * <p>turn 级状态基于<b>库里的全量目标</b>重算，不能用本批算：本批全失败不等于整轮全失败，
     * 那会把上一次成功的目标也说成没查成，用户看到的文案与可做动作都跟着错。
     *
     * <p><b>漏斗计数与精排标记在 §19.7 里没有规定</b>，这里的处理是：{@code after_rerank} 取库里
     * 的全量来源条数（它就是最终来源集合的大小，能数出来）；{@code channel_hits} 与
     * {@code after_dedup} 累加本次重放的检索工作量（漏斗记的是这一轮累计做了多少检索）；
     * {@code rerank_applied} 与 {@code degradation} 用最近一次精排的实际结果。这是规格空白处的
     * 判断，不是规格要求——若日后 §19.7 补全了重放语义，以文档为准改这里。
     *
     * @return 重算后的 turn 级状态，供调用方构造返回给上层的结果
     */
    @Transactional
    public TurnState merge(RetrievalOutcome replay) {
        UUID turnId = replay.turnId();
        for (var target : replay.targets()) {
            jdbc.update(
                    "delete from qa_retrieval_source where turn_id = ? and action_id = ?",
                    turnId,
                    target.actionId());
            jdbc.update(
                    "delete from qa_retrieval_target where turn_id = ? and action_id = ?",
                    turnId,
                    target.actionId());
        }
        insertTargets(turnId, replay.targets());

        var keptChunks = Set.copyOf(jdbc.queryForList(
                "select chunk_id from qa_retrieval_source where turn_id = ?", Long.class, turnId));
        var fresh = replay.sources().stream()
                .filter(source -> !keptChunks.contains(source.candidate().chunkId()))
                .toList();
        Integer ordinalBase = jdbc.queryForObject(
                "select coalesce(max(ordinal) + 1, 0) from qa_retrieval_source where turn_id = ?",
                Integer.class,
                turnId);
        insertSources(turnId, fresh, ordinalBase == null ? 0 : ordinalBase);

        int succeeded = countTargets(turnId, "SUCCEEDED");
        int failed = countTargets(turnId, "FAILED");
        int afterRerank = jdbc.queryForObject(
                "select count(*) from qa_retrieval_source where turn_id = ?", Integer.class, turnId);
        // 与 RetrievalOrchestrator.statusOf / partialOf 同一套规则（裁决 2026-08-11，契约 §9.1）：
        // 有来源就是 RETRIEVED；没有来源时才用目标状态分辨"没查成"与"查了但没有"。
        // 两处必须一致——重放后状态跟着变一套算法，是那种只在重试路径上出现、极难复现的 bug。
        RetrievalStatus status;
        if (afterRerank > 0) {
            status = RetrievalStatus.RETRIEVED;
        } else if (failed > 0 && succeeded == 0) {
            status = RetrievalStatus.RETRIEVAL_FAILED;
        } else {
            status = RetrievalStatus.NO_RESULT;
        }
        boolean partial = failed > 0 && (succeeded > 0 || afterRerank > 0);

        jdbc.update(
                """
                update qa_turn_retrieval
                   set status = ?, partial = ?, rerank_applied = ?, degradation = ?,
                       channel_hits = channel_hits + ?, after_dedup = after_dedup + ?,
                       after_rerank = ?, completed_at = now()
                 where turn_id = ?
                """,
                status.name(),
                partial,
                replay.reranked(),
                replay.degradation() == null ? null : replay.degradation().name(),
                replay.funnel().channelHits(),
                replay.funnel().afterDedup(),
                afterRerank,
                turnId);
        return new TurnState(status, partial, afterRerank);
    }

    private int countTargets(UUID turnId, String status) {
        Integer count = jdbc.queryForObject(
                "select count(*) from qa_retrieval_target where turn_id = ? and status = ?",
                Integer.class,
                turnId,
                status);
        return count == null ? 0 : count;
    }

    private void insertTargets(UUID turnId, List<RetrievalOutcome.TargetOutcome> targets) {
        for (var target : targets) {
            jdbc.update(
                    """
                    insert into qa_retrieval_target
                        (id, turn_id, action_id, kb_id, status, failure_code, latency_ms)
                    values (?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    turnId,
                    target.actionId(),
                    target.knowledgeBaseId(),
                    target.succeeded() ? "SUCCEEDED" : "FAILED",
                    target.failureCode() == null ? null : target.failureCode().name(),
                    (int) target.latencyMs());
        }
    }

    private void insertSources(UUID turnId, List<RetrievalOutcome.Source> sources, int ordinalBase) {
        int ordinal = ordinalBase;
        for (var source : sources) {
            jdbc.update(
                    """
                    insert into qa_retrieval_source
                        (id, turn_id, subquestion_id, action_id, ordinal,
                         chunk_id, kb_id, doc_id, origin_channel)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID(),
                    turnId,
                    source.subQuestionId(),
                    source.actionId(),
                    ordinal++,
                    source.candidate().chunkId(),
                    source.candidate().kbId(),
                    source.candidate().docId(),
                    source.candidate().originChannel().name());
        }
    }

    /**
     * 一轮检索的落库快照，供 API 组装 {@code retrieval} 块（契约 §9）。
     *
     * <p><b>只有 id 和序号，没有分数、没有正文、没有通道名。</b>来源表本来就不存分数
     * （数据模型 §14.2），{@code origin_channel} 存了但刻意不读出来——它只进处理详情，
     * 不进这里，避免"命中两次更可信"的错误暗示。展示用的库名/文档名由知识库侧的
     * {@code ChunkDisplayLookup} 另取，问答模块不拼 chunk 表 SQL。
     */
    public record RetrievalView(
            RetrievalStatus status,
            boolean partial,
            boolean rerankApplied,
            String degradation,
            List<SourceRow> sources,
            List<FailedTargetRow> failedTargets) {}

    public record SourceRow(int ordinal, long chunkId) {}

    public record FailedTargetRow(Long knowledgeBaseId, String failureCode) {}

    /**
     * 读取该 turn 的检索结果；turn 未进过 3B 时返回 empty（契约 §9：此时 {@code retrieval=null}）。
     *
     * <p>失败目标按知识库去重：一个库可能对应多条动作（多个子问题都定向它），但对用户而言
     * "这个库没查成"是一条信息，列两遍只是噪声。
     */
    public Optional<RetrievalView> findView(UUID turnId) {
        var head = jdbc.query(
                """
                select status, partial, rerank_applied, degradation
                  from qa_turn_retrieval where turn_id = ?
                """,
                (rs, rowNum) -> new Object[] {
                    RetrievalStatus.valueOf(rs.getString("status")),
                    rs.getBoolean("partial"),
                    rs.getBoolean("rerank_applied"),
                    rs.getString("degradation")
                },
                turnId);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        var row = head.get(0);
        var sources = jdbc.query(
                "select ordinal, chunk_id from qa_retrieval_source where turn_id = ? order by ordinal",
                (rs, rowNum) -> new SourceRow(rs.getInt("ordinal"), rs.getLong("chunk_id")),
                turnId);
        var failedTargets = jdbc.query(
                """
                select distinct kb_id, failure_code
                  from qa_retrieval_target
                 where turn_id = ? and status = 'FAILED'
                 order by kb_id
                """,
                (rs, rowNum) -> new FailedTargetRow(
                        rs.getObject("kb_id", Long.class), rs.getString("failure_code")),
                turnId);
        return Optional.of(new RetrievalView(
                (RetrievalStatus) row[0],
                (boolean) row[1],
                (boolean) row[2],
                (String) row[3],
                sources,
                failedTargets));
    }

    public Optional<RetrievalStatus> findStatus(UUID turnId) {
        return jdbc
                .query(
                        "select status from qa_turn_retrieval where turn_id = ?",
                        (rs, rowNum) -> RetrievalStatus.valueOf(rs.getString("status")),
                        turnId)
                .stream()
                .findFirst();
    }

    public List<UUID> findFailedActionIds(UUID turnId) {
        return jdbc.queryForList(
                "select action_id from qa_retrieval_target where turn_id = ? and status = 'FAILED'",
                UUID.class,
                turnId);
    }
}
