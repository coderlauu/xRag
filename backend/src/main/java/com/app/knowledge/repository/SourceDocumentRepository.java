package com.app.knowledge.repository;

import com.app.knowledge.model.ChunkStrategy;
import com.app.knowledge.model.DocumentStatus;
import com.app.knowledge.model.SourceDocument;
import com.app.knowledge.model.SourceType;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code source_document} 表的数据访问。
 *
 * <p>**所有查询方法必须带 {@code deleted = false}**（同 {@link KnowledgeBaseRepository}）。
 */
@Repository
public class SourceDocumentRepository {

    private static final String COLUMNS = """
            id, kb_id, name, source_type, file_key, file_size, content_type, source_uri,
            status, revision, chunk_count, error_message, enabled,
            chunk_strategy, chunk_size, chunk_overlap,
            sync_enabled, sync_cron, next_sync_time, last_sync_time, create_time
            """;

    private static final RowMapper<SourceDocument> MAPPER = (rs, rowNum) -> new SourceDocument(
            rs.getLong("id"),
            rs.getLong("kb_id"),
            rs.getString("name"),
            SourceType.valueOf(rs.getString("source_type")),
            rs.getString("file_key"),
            rs.getObject("file_size", Long.class),
            rs.getString("content_type"),
            rs.getString("source_uri"),
            DocumentStatus.valueOf(rs.getString("status")),
            rs.getInt("revision"),
            rs.getInt("chunk_count"),
            rs.getString("error_message"),
            rs.getBoolean("enabled"),
            ChunkStrategy.valueOf(rs.getString("chunk_strategy")),
            new SourceDocument.ChunkSize(rs.getInt("chunk_size"), rs.getInt("chunk_overlap")),
            rs.getBoolean("sync_enabled"),
            rs.getString("sync_cron"),
            rs.getObject("next_sync_time", OffsetDateTime.class),
            rs.getObject("last_sync_time", OffsetDateTime.class),
            rs.getObject("create_time", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public SourceDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertFile(long kbId, String name, String fileKey, long fileSize, String contentType,
            ChunkStrategy strategy, int chunkSize, int overlap) {
        return jdbc.queryForObject("""
                insert into source_document
                    (kb_id, name, source_type, file_key, file_size, content_type,
                     chunk_strategy, chunk_size, chunk_overlap)
                values (?, ?, 'FILE', ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, kbId, name, fileKey, fileSize, contentType,
                strategy.name(), chunkSize, overlap);
    }

    public Optional<SourceDocument> findById(long id) {
        return jdbc.query("select " + COLUMNS + " from source_document where id = ? and deleted = false",
                MAPPER, id).stream().findFirst();
    }

    /**
     * {@code status} / {@code enabled} 为 null 表示不过滤。条件是动态拼的，但拼的只是
     * 固定的 SQL 片段，值一律走占位符——不存在把用户输入拼进 SQL 的路径。
     */
    public List<SourceDocument> findPage(long kbId, DocumentStatus status, Boolean enabled, int page, int size) {
        StringBuilder sql = new StringBuilder(
                "select " + COLUMNS + " from source_document where kb_id = ? and deleted = false");
        List<Object> args = new ArrayList<>();
        args.add(kbId);
        appendFilters(sql, args, status, enabled);
        sql.append(" order by id desc limit ? offset ?");
        args.add(size);
        args.add((long) (page - 1) * size);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    public long count(long kbId, DocumentStatus status, Boolean enabled) {
        StringBuilder sql = new StringBuilder(
                "select count(*) from source_document where kb_id = ? and deleted = false");
        List<Object> args = new ArrayList<>();
        args.add(kbId);
        appendFilters(sql, args, status, enabled);
        Long total = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return total == null ? 0 : total;
    }

    /**
     * CAS 抢占：一条 SQL 同时完成"检查不在处理中"和"占位为处理中"。
     *
     * <p>影响 0 行 = 文档不存在、已删除、或已经在处理中。**检查与占位之间没有窗口**，
     * 因此手动触发与定时同步抢同一份文档时先到先得，不需要任何锁（ADR 0002）。
     */
    public boolean claimForProcessing(long docId) {
        return jdbc.update("""
                update source_document
                   set status = 'RUNNING', error_message = null, update_time = now()
                 where id = ? and deleted = false and status <> 'RUNNING'
                """, docId) == 1;
    }

    public void markSuccess(long docId, int revision, int chunkCount) {
        jdbc.update("""
                update source_document
                   set status = 'SUCCESS', revision = ?, chunk_count = ?, error_message = null,
                       update_time = now()
                 where id = ?
                """, revision, chunkCount, docId);
    }

    public void markFailed(long docId, String errorMessage) {
        jdbc.update("""
                update source_document set status = 'FAILED', error_message = ?, update_time = now()
                 where id = ?
                """, errorMessage == null || errorMessage.length() <= 2048 ? errorMessage
                        : errorMessage.substring(0, 2045) + "...", docId);
    }

    /** 手动新增分块后递增冗余计数。 */
    public void incrementChunkCount(long docId) {
        jdbc.update("""
                update source_document set chunk_count = chunk_count + 1, update_time = now()
                 where id = ?
                """, docId);
    }

    /**
     * 删除单个分块后递减冗余计数（data-model §4）。
     *
     * <p>{@code case when chunk_count > 0} 这层防护成本是几个字符，收益是这个字段永远不会
     * 出现负数。它正常不该为 0，但冗余计数与实际行数不严格一致这种事，早期 bug、手工改库、
     * 并发回滚都可能造成。
     */
    public void decrementChunkCount(long docId) {
        jdbc.update("""
                update source_document
                   set chunk_count = case when chunk_count > 0 then chunk_count - 1 else 0 end,
                       update_time = now()
                 where id = ?
                """, docId);
    }

    /** 启动回收用：单实例下进程刚起来时不可能有真正在跑的任务，所以这个判断完全准确。 */
    public List<Long> findRunningIds() {
        return jdbc.queryForList(
                "select id from source_document where status = 'RUNNING' and deleted = false", Long.class);
    }

    private void appendFilters(StringBuilder sql, List<Object> args, DocumentStatus status, Boolean enabled) {
        if (status != null) {
            sql.append(" and status = ?");
            args.add(status.name());
        }
        if (enabled != null) {
            sql.append(" and enabled = ?");
            args.add(enabled);
        }
    }
}
