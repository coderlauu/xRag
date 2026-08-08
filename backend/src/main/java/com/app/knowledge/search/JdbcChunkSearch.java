package com.app.knowledge.search;

import com.app.knowledge.keyword.ChineseTokenizer;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ChunkSearchPort} 的唯一实现。
 *
 * <p>两个方法都标 {@code @Transactional(readOnly = true)}：向量检索需要
 * {@code set local hnsw.iterative_scan}，而 {@code set local} 只在事务内有效。
 * <b>这就是"每个并行检索任务独占一条连接"的来源</b>，也是 ADR 0008 决策 5 那条
 * "并行度 &lt; 连接池"硬约束的物理依据。
 */
@Component
class JdbcChunkSearch implements ChunkSearchPort {

    private final JdbcTemplate jdbc;
    private final ChineseTokenizer tokenizer;

    JdbcChunkSearch(JdbcTemplate jdbc, ChineseTokenizer tokenizer) {
        this.jdbc = jdbc;
        this.tokenizer = tokenizer;
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChunkMatch> searchByVector(Set<Long> kbIds, float[] queryVector, int limit) {
        requireLimit(limit);
        jdbc.execute("set local hnsw.iterative_scan = strict_order");
        String literal = toVectorLiteral(queryVector);
        // 距离越小越相关，取负数作为 score，好让"score 大者在前"在两个通道上是同一个方向。
        String sql = """
                select chunk_id, kb_id, doc_id, -(embedding <=> ?::vector) as score
                  from document_chunk_embedding
                """
                + kbFilter(kbIds)
                + """
                 order by embedding <=> ?::vector
                 limit ?
                """;
        Object[] args = kbIds.isEmpty()
                ? new Object[] {literal, literal, limit}
                : concat(literal, kbIds, literal, limit);
        return jdbc.query(sql, JdbcChunkSearch::mapMatch, args);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ChunkMatch> searchByKeyword(Set<Long> kbIds, String rawQuery, int limit) {
        requireLimit(limit);
        String tsquery = tokenizer.toTsqueryInput(rawQuery);
        if (tsquery == null) {
            // 切不出 token（纯标点、空串）——关键词通道没有结果，不是故障。
            // 也绝不能拼一个空 tsquery 交给数据库，那会直接抛错。
            return List.of();
        }
        String sql = """
                select chunk_id, kb_id, doc_id,
                       ts_rank_cd(tsv, to_tsquery('simple', ?)) as score
                  from document_chunk_keyword
                 where tsv @@ to_tsquery('simple', ?)
                """
                + (kbIds.isEmpty() ? "" : " and kb_id in (" + placeholders(kbIds.size()) + ")")
                + """
                 order by score desc
                 limit ?
                """;
        Object[] args = kbIds.isEmpty()
                ? new Object[] {tsquery, tsquery, limit}
                : concat(tsquery, tsquery, kbIds, limit);
        return jdbc.query(sql, JdbcChunkSearch::mapMatch, args);
    }

    private static ChunkMatch mapMatch(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ChunkMatch(
                rs.getLong("chunk_id"), rs.getLong("kb_id"), rs.getLong("doc_id"), rs.getDouble("score"));
    }

    private static String kbFilter(Set<Long> kbIds) {
        return kbIds.isEmpty() ? "" : " where kb_id in (" + placeholders(kbIds.size()) + ")";
    }

    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }

    private static Object[] concat(Object first, Set<Long> kbIds, Object... rest) {
        Object[] args = new Object[1 + kbIds.size() + rest.length];
        args[0] = first;
        int index = 1;
        for (Long kbId : kbIds) {
            args[index++] = kbId;
        }
        for (Object value : rest) {
            args[index++] = value;
        }
        return args;
    }

    private static Object[] concat(Object first, Object second, Set<Long> kbIds, Object last) {
        Object[] args = new Object[3 + kbIds.size()];
        args[0] = first;
        args[1] = second;
        int index = 2;
        for (Long kbId : kbIds) {
            args[index++] = kbId;
        }
        args[index] = last;
        return args;
    }

    private static void requireLimit(int limit) {
        if (limit <= 0 || limit > 200) {
            throw new IllegalArgumentException("检索条数必须在 1 到 200 之间");
        }
    }

    private static String toVectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                literal.append(',');
            }
            literal.append(vector[i]);
        }
        return literal.append(']').toString();
    }
}
