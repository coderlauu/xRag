package com.app.knowledge.vector;

import com.app.knowledge.keyword.ChunkKeywordRepository;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * 分块**派生索引**的唯一入口：{@code document_chunk_embedding} 与
 * {@code document_chunk_keyword}（3B 新增，ADR 0008）。
 *
 * <p>这两张表是唯一不做逻辑删除的（PRD §7.6 例外 1）：它们是派生索引不是主数据，
 * 逻辑删除既没有业务意义，又会污染召回、拖慢检索。
 *
 * <p>Phase 3 检索侧依赖一条不变量：**表中存在的每一行，都对应一个未删除且已启用的分块**。
 * 所以任何让分块失效的操作（删除、禁用、重新分块）都必须在同一事务里物理删掉对应索引。
 *
 * <p><b>关键词索引写在这里而不是各调用点，是为了让"漏写一张"不可能发生。</b>
 * 向量的插入/删除有十来个调用点（入库、重新分块、单块增删改、批量启停）。如果关键词索引
 * 需要在每一处各补一次，早晚会漏——而漏写没有任何报错，症状只是关键词通道悄悄少召回。
 * 把两张表绑在同一批方法里，行集合一致就成了结构性事实，测试只需要正面确认它。
 */
@Repository
public class ChunkVectorRepository {

    public record SearchHit(long chunkId, long docId, double distance) {}

    private final JdbcTemplate jdbc;
    private final ChunkKeywordRepository keywords;

    public ChunkVectorRepository(JdbcTemplate jdbc, ChunkKeywordRepository keywords) {
        this.jdbc = jdbc;
        this.keywords = keywords;
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
        keywords.indexAll(kbId, docId, chunkIds);
    }

    public int deleteByDocId(long docId) {
        keywords.deleteByDocId(docId);
        return jdbc.update("delete from document_chunk_embedding where doc_id = ?", docId);
    }

    /**
     * 单个分块的向量删除。分块内容更新时的"删旧插新"用它——{@code chunk_id} 是主键，
     * 删完再插同一个 id 不会产生重复行（本类注释的不变量 3）。
     */
    public int deleteByChunkId(long chunkId) {
        keywords.deleteByChunkId(chunkId);
        return jdbc.update("delete from document_chunk_embedding where chunk_id = ?", chunkId);
    }

    public int deleteByChunkIds(Collection<Long> chunkIds) {
        if (chunkIds.isEmpty()) {
            return 0;
        }
        keywords.deleteByChunkIds(chunkIds);
        String placeholders = String.join(",", Collections.nCopies(chunkIds.size(), "?"));
        return jdbc.update("delete from document_chunk_embedding where chunk_id in ("
                + placeholders + ")", chunkIds.toArray());
    }

    public long countByDocId(long docId) {
        Long total = jdbc.queryForObject(
                "select count(*) from document_chunk_embedding where doc_id = ?", Long.class, docId);
        return total == null ? 0 : total;
    }

    /**
     * 共享向量表的检索入口。知识库 ID 是必填参数，调用方不存在“不小心忘记过滤”的接口。
     */
    @Transactional(readOnly = true)
    public List<SearchHit> search(long kbId, float[] queryVector, int limit) {
        if (limit <= 0 || limit > 100) {
            throw new IllegalArgumentException("向量检索条数必须在 1 到 100 之间");
        }
        jdbc.execute("set local hnsw.iterative_scan = strict_order");
        String literal = toVectorLiteral(queryVector);
        return jdbc.query("""
                select chunk_id, doc_id, embedding <=> ?::vector as distance
                  from document_chunk_embedding
                 where kb_id = ?
                 order by embedding <=> ?::vector
                 limit ?
                """, (rs, rowNum) -> new SearchHit(
                        rs.getLong("chunk_id"),
                        rs.getLong("doc_id"),
                        rs.getDouble("distance")),
                literal, kbId, literal, limit);
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
