package com.app.knowledge.repository;

import com.app.knowledge.model.IngestionPhase;
import com.app.knowledge.model.IngestionRun;
import com.app.knowledge.model.IngestionRunStatus;
import com.app.knowledge.model.IngestionTriggerSource;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * {@code ingestion_run} 既是执行记录也是任务队列（ADR 0002：数据库任务表替代 MQ）。
 *
 * <p>本表**不做逻辑删除**——它是日志性质的记录，没有"删除"这个操作，因此查询里
 * 没有 {@code deleted = false}，这是有意的，不是漏写。
 */
@Repository
public class IngestionRunRepository {

    private static final String COLUMNS = """
            id, kb_id, doc_id, trigger_source, status, phase, revision, chunk_count,
            error_message, started_time, finished_time, create_time
            """;

    private static final RowMapper<IngestionRun> MAPPER = (rs, rowNum) -> new IngestionRun(
            rs.getLong("id"),
            rs.getLong("kb_id"),
            rs.getLong("doc_id"),
            IngestionTriggerSource.valueOf(rs.getString("trigger_source")),
            IngestionRunStatus.valueOf(rs.getString("status")),
            rs.getString("phase") == null ? null : IngestionPhase.valueOf(rs.getString("phase")),
            rs.getObject("revision", Integer.class),
            rs.getObject("chunk_count", Integer.class),
            rs.getString("error_message"),
            rs.getObject("started_time", OffsetDateTime.class),
            rs.getObject("finished_time", OffsetDateTime.class),
            rs.getObject("create_time", OffsetDateTime.class));

    private final JdbcTemplate jdbc;

    public IngestionRunRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long insertQueued(long kbId, long docId, IngestionTriggerSource triggerSource) {
        return jdbc.queryForObject("""
                insert into ingestion_run (kb_id, doc_id, trigger_source, status)
                values (?, ?, ?, 'QUEUED')
                returning id
                """, Long.class, kbId, docId, triggerSource.name());
    }

    public List<Long> findQueuedIds(int limit) {
        return jdbc.queryForList(
                "select id from ingestion_run where status = 'QUEUED' order by id limit ?",
                Long.class, limit);
    }

    /**
     * CAS 抢占：只有把 {@code QUEUED} 改成 {@code RUNNING} 成功的那一方才拿到执行权。
     * 影响 0 行说明别人已经抢走了，直接跳过——检查与占用合并在一条 SQL 里，
     * 中间没有窗口，所以不需要锁（ADR 0002）。
     */
    public boolean claim(long runId) {
        return jdbc.update("""
                update ingestion_run
                   set status = 'RUNNING', started_time = now(), heartbeat_time = now()
                 where id = ? and status = 'QUEUED'
                """, runId) == 1;
    }

    public void updatePhase(long runId, IngestionPhase phase) {
        jdbc.update("update ingestion_run set phase = ?, heartbeat_time = now() where id = ?",
                phase.name(), runId);
    }

    public void heartbeat(long runId) {
        jdbc.update("update ingestion_run set heartbeat_time = now() where id = ?", runId);
    }

    public void markSuccess(long runId, int revision, int chunkCount) {
        jdbc.update("""
                update ingestion_run
                   set status = 'SUCCESS', phase = null, revision = ?, chunk_count = ?,
                       finished_time = now(), heartbeat_time = now()
                 where id = ?
                """, revision, chunkCount, runId);
    }

    /** {@code phase} 保留失败发生时所处的步骤，不清空——它是这条记录最有价值的诊断信息。 */
    public void markFailed(long runId, String errorMessage) {
        jdbc.update("""
                update ingestion_run
                   set status = 'FAILED', error_message = ?, finished_time = now()
                 where id = ? and status not in ('SUCCESS', 'FAILED')
                """, truncate(errorMessage), runId);
    }

    public Optional<IngestionRun> findById(long runId) {
        return jdbc.query("select " + COLUMNS + " from ingestion_run where id = ?", MAPPER, runId)
                .stream().findFirst();
    }

    public Optional<IngestionRun> findLatestByDocId(long docId) {
        return jdbc.query("select " + COLUMNS + " from ingestion_run where doc_id = ? order by id desc limit 1",
                MAPPER, docId).stream().findFirst();
    }

    public List<IngestionRun> findPageByDocId(long docId, int page, int size) {
        return jdbc.query("select " + COLUMNS
                        + " from ingestion_run where doc_id = ? order by id desc limit ? offset ?",
                MAPPER, docId, size, (long) (page - 1) * size);
    }

    public long countByDocId(long docId) {
        Long total = jdbc.queryForObject("select count(*) from ingestion_run where doc_id = ?",
                Long.class, docId);
        return total == null ? 0 : total;
    }

    /** error_message 列宽 4096，异常链拼起来很容易超——超了整条 update 会失败，把失败原因也弄丢。 */
    private String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= 4096 ? message : message.substring(0, 4093) + "...";
    }
}
