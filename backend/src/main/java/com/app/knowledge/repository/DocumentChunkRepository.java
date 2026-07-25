package com.app.knowledge.repository;

import com.app.knowledge.model.TextChunk;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * {@code document_chunk} 的数据访问。查询方法一律带 {@code deleted = false}。
 */
@Repository
public class DocumentChunkRepository {

    private final JdbcTemplate jdbc;

    public DocumentChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 批量插入并按顺序返回生成的 id——向量表要用这些 id 做主键。 */
    public List<Long> insertAll(long kbId, long docId, int revision, List<TextChunk> chunks) {
        List<Long> ids = new ArrayList<>(chunks.size());
        for (TextChunk chunk : chunks) {
            ids.add(jdbc.queryForObject("""
                    insert into document_chunk
                        (kb_id, doc_id, revision, chunk_index, content, char_count, token_count, content_hash)
                    values (?, ?, ?, ?, ?, ?, ?, ?)
                    returning id
                    """, Long.class, kbId, docId, revision, chunk.index(), chunk.content(),
                    chunk.charCount(), chunk.tokenCount(), chunk.contentHash()));
        }
        return ids;
    }

    /**
     * 逻辑删除该文档**旧版本**的分块。重新分块时的"删旧插新"——首次分块时影响 0 行，
     * 所以首次和重新分块可以走完全同一段代码，不需要分支。
     */
    public int softDeleteOlderRevisions(long docId, int newRevision) {
        return jdbc.update("""
                update document_chunk set deleted = true, delete_time = now(), update_time = now()
                 where doc_id = ? and revision < ? and deleted = false
                """, docId, newRevision);
    }

    public long countByDocId(long docId) {
        Long total = jdbc.queryForObject(
                "select count(*) from document_chunk where doc_id = ? and deleted = false", Long.class, docId);
        return total == null ? 0 : total;
    }
}
