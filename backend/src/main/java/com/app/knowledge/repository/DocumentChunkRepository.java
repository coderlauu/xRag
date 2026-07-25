package com.app.knowledge.repository;

import com.app.knowledge.model.DocumentChunk;
import com.app.knowledge.model.TextChunk;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code document_chunk} 的数据访问。查询方法一律带 {@code deleted = false}。
 */
@Repository
public class DocumentChunkRepository {

    private static final String COLUMNS = """
            id, doc_id, revision, chunk_index, content, char_count, token_count,
            enabled, create_time, update_time
            """;

    private static final RowMapper<DocumentChunk> MAPPER = (rs, rowNum) -> new DocumentChunk(
            rs.getLong("id"),
            rs.getLong("doc_id"),
            rs.getInt("revision"),
            rs.getInt("chunk_index"),
            rs.getString("content"),
            rs.getInt("char_count"),
            rs.getInt("token_count"),
            rs.getBoolean("enabled"),
            rs.getObject("create_time", OffsetDateTime.class),
            rs.getObject("update_time", OffsetDateTime.class));

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

    public Optional<DocumentChunk> findById(long id) {
        return jdbc.query("select " + COLUMNS + " from document_chunk where id = ? and deleted = false",
                MAPPER, id).stream().findFirst();
    }

    /**
     * 按 {@code chunk_index} 升序分页——用户是照着原文顺序浏览的，按 id 排会把手动新增的
     * 分块甩到末尾（api.md §4）。
     *
     * <p>**次级排序键 {@code id} 不是装饰**：{@code chunk_index} 允许重复（data-model §3.3），
     * 只按它排序时同号分块之间的相对顺序由数据库自行决定、每次查询都可能不同，翻页就会
     * 出现某条重复出现或整条漏掉。加上主键才构成全序，分页结果才是稳定的。
     */
    public List<DocumentChunk> findPage(long docId, Boolean enabled, int page, int size) {
        StringBuilder sql = new StringBuilder(
                "select " + COLUMNS + " from document_chunk where doc_id = ? and deleted = false");
        List<Object> args = new ArrayList<>();
        args.add(docId);
        if (enabled != null) {
            sql.append(" and enabled = ?");
            args.add(enabled);
        }
        sql.append(" order by chunk_index asc, id asc limit ? offset ?");
        args.add(size);
        args.add((long) (page - 1) * size);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count(long docId, Boolean enabled) {
        StringBuilder sql = new StringBuilder(
                "select count(*) from document_chunk where doc_id = ? and deleted = false");
        List<Object> args = new ArrayList<>();
        args.add(docId);
        if (enabled != null) {
            sql.append(" and enabled = ?");
            args.add(enabled);
        }
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    /** {@code charCount} / {@code tokenCount} / {@code contentHash} 一律服务端重算，不接受客户端传值。 */
    public void updateContent(long id, String content, int charCount, int tokenCount, String contentHash) {
        jdbc.update("""
                update document_chunk
                   set content = ?, char_count = ?, token_count = ?, content_hash = ?, update_time = now()
                 where id = ? and deleted = false
                """, content, charCount, tokenCount, contentHash, id);
    }

    /**
     * 追加分块时的默认序号来源，取 {@code max(chunk_index) + 1}。
     *
     * <p>**这是全模块唯一一个有意不带 {@code deleted = false} 的查询**（DEL-08 专门守它）。
     * 用 {@code count(*)} 或只数存活行都会算出一个偏小的序号：分块可以被删除，删掉中间几个
     * 之后 count 就小于实际最大序号，新分块会和现存分块撞号。已删除分块的序号必须继续
     * 被"占着"，否则序号的含义（在原文中的位置）就乱了。
     *
     * @return 文档一个分块都没有过时返回 {@code -1}，调用方 +1 得到 0
     */
    public int maxChunkIndex(long docId) {
        Integer max = jdbc.queryForObject(
                "select coalesce(max(chunk_index), -1) from document_chunk where doc_id = ?",
                Integer.class, docId);
        return max == null ? -1 : max;
    }

    /**
     * 批量按 id 取分块，**同时限定 {@code doc_id}**——归属校验和查询合并成一步，调用方拿到
     * 的行数少于请求数就说明有 id 无效或不属于这个文档，不需要再查一次。
     */
    public List<DocumentChunk> findAllByDocIdAndIds(long docId, Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids.size() + 1);
        args.add(docId);
        args.addAll(ids);
        return jdbc.query("select " + COLUMNS + " from document_chunk"
                + " where doc_id = ? and deleted = false and id in (" + placeholders + ")",
                MAPPER, args.toArray());
    }

    public void setEnabledAll(Collection<Long> ids, boolean enabled) {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>(ids.size() + 1);
        args.add(enabled);
        args.addAll(ids);
        jdbc.update("update document_chunk set enabled = ?, update_time = now()"
                + " where deleted = false and id in (" + placeholders + ")", args.toArray());
    }

    public void setEnabled(long id, boolean enabled) {
        jdbc.update("""
                update document_chunk set enabled = ?, update_time = now()
                 where id = ? and deleted = false
                """, enabled, id);
    }

    public void softDelete(long id) {
        jdbc.update("""
                update document_chunk set deleted = true, delete_time = now(), update_time = now()
                 where id = ? and deleted = false
                """, id);
    }
}
