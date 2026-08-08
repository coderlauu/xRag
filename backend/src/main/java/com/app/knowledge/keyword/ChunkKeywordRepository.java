package com.app.knowledge.keyword;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code document_chunk_keyword} 的唯一入口（数据模型 §13）。
 *
 * <p>形态与 {@code document_chunk_embedding} 逐条对齐，三条不变量同源：只做物理删除、
 * 每行对应一个未删除且启用的分块、{@code chunk_id} 作主键。
 *
 * <p><b>tsv 从 {@code document_chunk.content} 现读而不是由调用方传入</b>，这是有意的：
 * 派生表应当派生于实际存储的正文。如果让调用方传文本，某天传进来的与真正落库的不一致，
 * 关键词索引就会描述另一段文本，而这没有任何报错。多一次读换掉整类错误。
 */
@Repository
public class ChunkKeywordRepository {

    private final JdbcTemplate jdbc;
    private final ChineseTokenizer tokenizer;

    public ChunkKeywordRepository(JdbcTemplate jdbc, ChineseTokenizer tokenizer) {
        this.jdbc = jdbc;
        this.tokenizer = tokenizer;
    }

    /**
     * 为指定分块建立关键词索引。正文按 {@code chunk_id} 从 {@code document_chunk} 批量读出，
     * 在应用侧切词后写入。
     */
    public void indexAll(long kbId, long docId, List<Long> chunkIds) {
        if (chunkIds.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(chunkIds.size(), "?"));
        var rows = jdbc.query(
                "select id, content from document_chunk where id in (" + placeholders + ")",
                (rs, rowNum) -> new Object[] {rs.getLong("id"), rs.getString("content")},
                chunkIds.toArray());
        for (Object[] row : rows) {
            jdbc.update(
                    """
                    insert into document_chunk_keyword (chunk_id, kb_id, doc_id, tsv)
                    values (?, ?, ?, to_tsvector('simple', ?))
                    """,
                    (Long) row[0],
                    kbId,
                    docId,
                    tokenizer.toTsvectorInput((String) row[1]));
        }
    }

    public int deleteByDocId(long docId) {
        return jdbc.update("delete from document_chunk_keyword where doc_id = ?", docId);
    }

    public int deleteByChunkId(long chunkId) {
        return jdbc.update("delete from document_chunk_keyword where chunk_id = ?", chunkId);
    }

    public int deleteByChunkIds(Collection<Long> chunkIds) {
        if (chunkIds.isEmpty()) {
            return 0;
        }
        String placeholders = String.join(",", Collections.nCopies(chunkIds.size(), "?"));
        return jdbc.update(
                "delete from document_chunk_keyword where chunk_id in (" + placeholders + ")",
                chunkIds.toArray());
    }

    public int deleteByKbId(long kbId) {
        return jdbc.update("delete from document_chunk_keyword where kb_id = ?", kbId);
    }

    public long countByDocId(long docId) {
        Long total = jdbc.queryForObject(
                "select count(*) from document_chunk_keyword where doc_id = ?", Long.class, docId);
        return total == null ? 0 : total;
    }
}
