package com.app.knowledge.keyword;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 存量分块的关键词索引回填（ADR 0008 决策 3、架构 §20.3）。
 *
 * <p><b>为什么不是纯 SQL 迁移</b>：切词在应用侧，V9 只能建表建索引，正文要读出来在 Java 里切完
 * 再写回去。所以它是一个批处理组件而不是 migration。
 *
 * <p><b>为什么默认关闭</b>：它是调度类组件，遵守跨模块纪律与 ADR 0002 的单实例假设——多个上下文
 * 连同一个库时，每个都会把同一批分块扫一遍。默认关闭也让集成测试不受它干扰。
 *
 * <p><b>未回填完成时关键词通道照常工作</b>，只是召回不全。这是已知降级不是故障：向量通道不受
 * 影响，用户拿到的结果仍然可用。
 */
@Service
public class KeywordBackfillService {

    private static final Logger LOGGER = LoggerFactory.getLogger(KeywordBackfillService.class);

    private final JdbcTemplate jdbc;
    private final ChunkKeywordRepository keywords;
    private final boolean enabled;
    private final int batchSize;

    public KeywordBackfillService(
            JdbcTemplate jdbc,
            ChunkKeywordRepository keywords,
            @Value("${app.knowledge.keyword-backfill.enabled:false}") boolean enabled,
            @Value("${app.knowledge.keyword-backfill.batch-size:200}") int batchSize) {
        this.jdbc = jdbc;
        this.keywords = keywords;
        this.enabled = enabled;
        if (batchSize < 1) {
            throw new IllegalArgumentException(
                    "app.knowledge.keyword-backfill.batch-size must be positive");
        }
        this.batchSize = batchSize;
    }

    /**
     * 回填缺失的关键词行。
     *
     * <p>幂等：只处理"向量表有、关键词表没有"的 {@code chunk_id}，重复执行不会改变已有行。
     * 按 {@code chunk_id} 游标分批，中途中断后重跑从缺口继续，不会跳过也不会重复。
     *
     * @return 本次补齐的行数
     */
    public int backfill() {
        if (!enabled) {
            LOGGER.debug("keyword backfill disabled; skipping");
            return 0;
        }
        int total = 0;
        long cursor = 0;
        while (true) {
            List<Long> batch = nextBatch(cursor);
            if (batch.isEmpty()) {
                break;
            }
            for (Long chunkId : batch) {
                // 逐条读 kb/doc 归属再写：批内不同分块可能属于不同文档，不能共用一组归属。
                var owner = jdbc.query(
                        "select kb_id, doc_id from document_chunk_embedding where chunk_id = ?",
                        (rs, rowNum) -> new long[] {rs.getLong("kb_id"), rs.getLong("doc_id")},
                        chunkId);
                if (owner.isEmpty()) {
                    // 刚被删掉——跳过，不是错误。
                    continue;
                }
                keywords.indexAll(owner.get(0)[0], owner.get(0)[1], List.of(chunkId));
                total++;
            }
            cursor = batch.get(batch.size() - 1);
        }
        if (total > 0) {
            LOGGER.info("keyword backfill completed: {} chunks indexed", total);
        }
        return total;
    }

    private List<Long> nextBatch(long cursor) {
        return jdbc.queryForList(
                """
                select e.chunk_id
                  from document_chunk_embedding e
                  left join document_chunk_keyword k on k.chunk_id = e.chunk_id
                 where e.chunk_id > ? and k.chunk_id is null
                 order by e.chunk_id
                 limit ?
                """,
                Long.class,
                cursor,
                batchSize);
    }

    public boolean isEnabled() {
        return enabled;
    }
}
