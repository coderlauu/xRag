package com.app.knowledge.vector;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code document_chunk_embedding} 的唯一入口。
 *
 * <p>这张表是五张表里**唯一不做逻辑删除**的（PRD §7.6 例外 1）：它是派生索引不是主数据，
 * 逻辑删除既没有业务意义，又会污染召回、拖慢检索。
 *
 * <p>Phase 3 检索侧依赖一条不变量：**本表中存在的每一行，都对应一个未删除且已启用的分块**。
 * 所以任何让分块失效的操作（删除、禁用、重新分块）都必须在同一事务里物理删掉对应向量。
 */
@Repository
public class ChunkVectorRepository {

    private final JdbcTemplate jdbc;

    public ChunkVectorRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 向量用 {@code ?::vector} 由文本字面量转换写入。
     *
     * <p>这样就不必为了一个类型引入 pgvector 的 JDBC 扩展——`vector` 的文本表示
     * 就是 {@code [1,2,3]}，PostgreSQL 侧的转换是确定的。
     */
    public void insertAll(long kbId, long docId, List<Long> chunkIds, List<float[]> vectors) {
        if (chunkIds.size() != vectors.size()) {
            throw new IllegalArgumentException(
                    "分块数与向量数不一致：%d vs %d".formatted(chunkIds.size(), vectors.size()));
        }
        for (int i = 0; i < chunkIds.size(); i++) {
            jdbc.update("""
                    insert into document_chunk_embedding (chunk_id, kb_id, doc_id, embedding)
                    values (?, ?, ?, ?::vector)
                    """, chunkIds.get(i), kbId, docId, toVectorLiteral(vectors.get(i)));
        }
    }

    public int deleteByDocId(long docId) {
        return jdbc.update("delete from document_chunk_embedding where doc_id = ?", docId);
    }

    public long countByDocId(long docId) {
        Long total = jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where doc_id = ?", Long.class, docId);
        return total == null ? 0 : total;
    }

    private String toVectorLiteral(float[] vector) {
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
