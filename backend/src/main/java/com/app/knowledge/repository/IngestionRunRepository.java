package com.app.knowledge.repository;

import com.app.knowledge.model.IngestionPhase;
import com.app.knowledge.model.IngestionRun;
import com.app.knowledge.model.IngestionRunStatus;
import com.app.knowledge.model.IngestionTriggerSource;
import com.app.knowledge.model.StaleRun;
import java.time.Duration;
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

    private static final RowMapper<StaleRun> STALE_MAPPER = (rs, rowNum) -> new StaleRun(
            rs.getLong("id"),
            rs.getLong("doc_id"),
            rs.getString("phase") == null ? null : IngestionPhase.valueOf(rs.getString("phase")),
            rs.getLong("alive_seconds"));

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

    /**
     * 定时同步检查过、内容没变。
     *
     * <p>**它不是失败**，界面上的措辞必须是"内容未变化，已跳过"而不是任何带警告色的说法
     * （ui-spec §5）——这是定时同步正常工作时最常见的结果，标成橙色会让用户每天早上都以为
     * 同步出了问题。{@code error_message} 借用来存跳过原因，是这张表唯一一处它不表示错误的地方。
     */
    public void markSkipped(long runId, String reason) {
        jdbc.update("""
                update ingestion_run
                   set status = 'SKIPPED', error_message = ?, finished_time = now()
                 where id = ? and status = 'QUEUED'
                """, truncate(reason), runId);
    }

    /** {@code phase} 保留失败发生时所处的步骤，不清空——它是这条记录最有价值的诊断信息。 */
    public void markFailed(long runId, String errorMessage) {
        jdbc.update("""
                update ingestion_run
                   set status = 'FAILED', error_message = ?, finished_time = now()
                 where id = ? and status not in ('SUCCESS', 'FAILED')
                """, truncate(errorMessage), runId);
    }

    /**
     * 心跳超时回收专用：与 {@link #markFailed} 的唯一区别是**把 {@code phase} 清空**。
     *
     * <p>{@code phase} 的语义是"失败发生在这一步"（见 {@link IngestionPhase}），界面据此
     * 渲染成「内容切分失败：…」这样的前缀。但被回收的任务恰恰**不知道失败在哪一步**——
     * 存着的只是心跳停止前最后写下的步骤，进程被杀时执行线程可能早已走过去了。真实案例：
     * 一份文档切分只需 3.5 毫秒却停在 {@code CHUNK}，界面于是显示「内容切分失败」，把人
     * 引去排查一个根本没出错的功能。这里留 {@code null}（"不知道"）才是诚实的，具体的
     * 最后步骤改由错误消息用准确措辞说明。
     */
    public void markStaleFailed(long runId, String errorMessage) {
        jdbc.update("""
                update ingestion_run
                   set status = 'FAILED', phase = null, error_message = ?, finished_time = now()
                 where id = ? and status not in ('SUCCESS', 'FAILED')
                """, truncate(errorMessage), runId);
    }

    /**
     * 心跳超时的僵尸任务：{@code RUNNING} 且心跳停更超过阈值。
     *
     * <p>与启动回收的区别是**它不依赖单实例假设**——判据是这条任务自己的心跳停了，而不是
     * "进程刚起来所以不可能有任务在跑"。所以多实例下这一层仍然成立，失效的只有启动回收
     * （ADR 0002）。
     *
     * <p>{@code heartbeat_time is null} 也算：CAS 抢占时就会写入心跳，为 null 说明这条记录
     * 处在一个不该出现的状态，放着不管它会永远卡住对应文档。
     */
    public List<StaleRun> findStale(Duration timeout) {
        return jdbc.query("""
                select id, doc_id, phase,
                       coalesce(floor(extract(epoch from (heartbeat_time - started_time))), -1)::bigint
                           as alive_seconds
                  from ingestion_run
                 where status = 'RUNNING'
                   and (heartbeat_time is null or heartbeat_time < now() - make_interval(secs => ?))
                """, STALE_MAPPER, (double) timeout.toMillis() / 1000);
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
