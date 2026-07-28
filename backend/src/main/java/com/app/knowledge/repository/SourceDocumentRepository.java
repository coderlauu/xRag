package com.app.knowledge.repository;

import com.app.knowledge.model.ChunkStrategy;
import com.app.knowledge.model.DocumentStatus;
import com.app.knowledge.model.IngestionInput;
import com.app.knowledge.model.SourceDocument;
import com.app.knowledge.model.SourceType;
import com.app.knowledge.model.SyncCandidate;
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
            id, kb_id, name, source_type, file_key, storage_object_id,
            file_size, content_type, source_uri,
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
            rs.getString("storage_object_id"),
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

    public long insertFile(long kbId, String name, String fileKey, String storageObjectId,
            long fileSize, String contentType, ChunkStrategy strategy, int chunkSize, int overlap) {
        return jdbc.queryForObject("""
                insert into source_document
                    (kb_id, name, source_type, file_key, storage_object_id, file_size, content_type,
                     chunk_strategy, chunk_size, chunk_overlap)
                values (?, ?, 'FILE', ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, kbId, name, fileKey, storageObjectId, fileSize, contentType,
                strategy.name(), chunkSize, overlap);
    }

    /** URL 来源。{@code http_etag} / {@code http_last_modified} 供工单 17 的两级变更检测使用。 */
    public long insertUrl(long kbId, String name, String sourceUri, String fileKey,
            String storageObjectId, long fileSize, String contentType,
            String contentHash, String etag, String lastModified,
            ChunkStrategy strategy, int chunkSize, int overlap,
            boolean syncEnabled, String syncCron, OffsetDateTime nextSyncTime) {
        return jdbc.queryForObject("""
                insert into source_document
                    (kb_id, name, source_type, source_uri, file_key, storage_object_id,
                     file_size, content_type,
                     content_hash, http_etag, http_last_modified,
                     chunk_strategy, chunk_size, chunk_overlap,
                     sync_enabled, sync_cron, next_sync_time)
                values (?, ?, 'URL', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                returning id
                """, Long.class, kbId, name, sourceUri, fileKey, storageObjectId,
                fileSize, contentType,
                contentHash, etag, lastModified, strategy.name(), chunkSize, overlap,
                syncEnabled, syncCron, nextSyncTime);
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

    public void markSuccess(long docId, int revision, int chunkCount, IngestionInput input) {
        int updated = jdbc.update("""
                update source_document
                   set status = 'SUCCESS', revision = ?, chunk_count = ?, error_message = null,
                       file_key = ?,
                       content_hash = coalesce(?, content_hash),
                       file_size = coalesce(?, file_size),
                       content_type = coalesce(?, content_type),
                       http_etag = coalesce(?, http_etag),
                       http_last_modified = coalesce(?, http_last_modified),
                       update_time = now()
                 where id = ? and deleted = false and status = 'RUNNING' and revision = ?
                """, revision, chunkCount, input.fileKey(), input.contentHash(),
                input.fileSize(), input.contentType(), input.httpEtag(), input.httpLastModified(),
                docId, input.revision());
        if (updated != 1) {
            throw new IllegalStateException("文档版本已经变化或文档已被删除，拒绝写入过期的入库结果");
        }
    }

    public void markFailed(long docId, String errorMessage) {
        jdbc.update("""
                update source_document set status = 'FAILED', error_message = ?, update_time = now()
                 where id = ?
                """, errorMessage == null || errorMessage.length() <= 2048 ? errorMessage
                        : errorMessage.substring(0, 2045) + "...", docId);
    }

    /** 只更新传了的字段，null 表示不改（api.md §3：全字段可选）。 */
    public void update(long docId, String name, ChunkStrategy strategy, Integer chunkSize,
            Integer overlap, String sourceUri, Boolean syncEnabled, String syncCron) {
        StringBuilder sql = new StringBuilder("update source_document set update_time = now()");
        List<Object> args = new ArrayList<>();
        appendIfPresent(sql, args, "name", name);
        appendIfPresent(sql, args, "chunk_strategy", strategy == null ? null : strategy.name());
        appendIfPresent(sql, args, "chunk_size", chunkSize);
        appendIfPresent(sql, args, "chunk_overlap", overlap);
        appendIfPresent(sql, args, "source_uri", sourceUri);
        appendIfPresent(sql, args, "sync_enabled", syncEnabled);
        appendIfPresent(sql, args, "sync_cron", syncCron);
        sql.append(" where id = ? and deleted = false");
        args.add(docId);
        jdbc.update(sql.toString(), args.toArray());
    }

    public void setEnabled(long docId, boolean enabled) {
        jdbc.update("update source_document set enabled = ?, update_time = now()"
                + " where id = ? and deleted = false", enabled, docId);
    }

    /**
     * 逻辑删除文档（data-model §4）。**顺手关掉 {@code sync_enabled}**：定时扫描的部分索引
     * 就不再覆盖这行——扫描 SQL 本身也带 {@code deleted = false}，但把它从索引里摘出去更干净。
     *
     * <p>分块的逻辑删除与向量的物理删除由 Service 在同一个事务里另外发起。
     */
    public void softDelete(long docId) {
        jdbc.update("""
                update source_document
                   set deleted = true, delete_time = now(), update_time = now(), sync_enabled = false
                 where id = ? and deleted = false
                """, docId);
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

    /**
     * 到期待检查的定时同步文档（architecture.md §3.3）。
     *
     * <p>{@code status <> 'RUNNING'} 是必要的：正在处理的文档再触发一次同步毫无意义，CAS 也会
     * 把它挡掉，但那样会白跑一次 HEAD 甚至一次完整下载。
     */
    public List<SyncCandidate> findDueForSync(int limit) {
        return jdbc.query("""
                select d.id, d.kb_id, kb.storage_alias, d.storage_object_id, d.name,
                       d.source_uri, d.file_key, d.file_size, d.content_type,
                       d.revision, d.content_hash, d.http_etag, d.http_last_modified, d.sync_cron
                  from source_document d
                  join knowledge_base kb on kb.id = d.kb_id and kb.deleted = false
                 where d.source_type = 'URL' and d.sync_enabled = true and d.deleted = false
                   and d.status <> 'RUNNING'
                   and d.next_sync_time is not null and d.next_sync_time <= now()
                 order by d.next_sync_time
                 limit ?
                """, (rs, rowNum) -> new SyncCandidate(
                        rs.getLong("id"), rs.getLong("kb_id"), rs.getString("storage_alias"),
                        rs.getString("storage_object_id"), rs.getString("name"),
                        rs.getString("source_uri"), rs.getString("file_key"),
                        rs.getObject("file_size", Long.class), rs.getString("content_type"),
                        rs.getInt("revision"), rs.getString("content_hash"),
                        rs.getString("http_etag"), rs.getString("http_last_modified"),
                        rs.getString("sync_cron")), limit);
    }

    /** 改了同步规则后重算下次执行时间。与 {@link #advanceSync} 的区别是它不动 last_sync_time。 */
    public void advanceNextSyncTime(long docId, OffsetDateTime nextSyncTime) {
        jdbc.update("update source_document set next_sync_time = ?, update_time = now() where id = ?",
                nextSyncTime, docId);
    }

    /** {@code contentHash} 为 null 表示"这次只更新 HTTP 头，内容没变"。 */
    public void updateSyncMeta(long docId, String etag, String lastModified, String contentHash) {
        if (contentHash == null) {
            jdbc.update("""
                    update source_document set http_etag = ?, http_last_modified = ?, update_time = now()
                     where id = ?
                    """, etag, lastModified, docId);
            return;
        }
        jdbc.update("""
                update source_document
                   set http_etag = ?, http_last_modified = ?, content_hash = ?, update_time = now()
                 where id = ?
                """, etag, lastModified, contentHash, docId);
    }

    /**
     * 推进调度。**每次检查（含跳过、含失败）都要调**，否则这篇文档会在每一轮扫描里被反复命中。
     *
     * @param nextSyncTime null 表示 cron 已不再合法，让它退出扫描范围而不是每轮重试注定失败的解析
     */
    public void advanceSync(long docId, OffsetDateTime nextSyncTime) {
        jdbc.update("""
                update source_document
                   set last_sync_time = now(), next_sync_time = ?, update_time = now()
                 where id = ?
                """, nextSyncTime, docId);
    }

    /** 启动回收用：单实例下进程刚起来时不可能有真正在跑的任务，所以这个判断完全准确。 */
    public List<Long> findRunningIds() {
        return jdbc.queryForList(
                "select id from source_document where status = 'RUNNING' and deleted = false", Long.class);
    }

    /** 拼的只是固定的 SQL 片段，值一律走占位符——不存在把用户输入拼进 SQL 的路径。 */
    private void appendIfPresent(StringBuilder sql, List<Object> args, String column, Object value) {
        if (value != null) {
            sql.append(", ").append(column).append(" = ?");
            args.add(value);
        }
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
