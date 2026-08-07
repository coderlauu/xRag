package com.app.ai.diagnostic;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * data-model.md §7 的诊断物理清理。
 *
 * <p>诊断是有强制保留期的运维事实，不走业务表的逻辑删除（PRD §7.6 的例外之一）。
 * 两条上限是"或"的关系：超过 {@code maxAgeDays} 的要删，超出最新 {@code maxLogicalCalls}
 * 条之外的也要删。子表由 {@code on delete cascade} 跟着走，不单独发语句。
 *
 * <p>**每批一个独立短事务，绝不开一个大删除事务。**一次要清理几万条时，单事务会长时间持锁，
 * 把正在写诊断的调用一起堵住——而诊断写入失败会被 writer 记成 write failure 并暴露到总览，
 * 等于清理任务自己制造了一次"看起来像故障"的事件。
 *
 * <p>breaker transition 只按时间清理，不受"最近 N 次调用"约束：它不等同于逻辑调用，
 * 也不对 logical call 建 FK。
 */
@Service
public class DiagnosticRetentionService {

    /** 与 data-model.md §7 一致：单条删除语句最多处理 1,000 条。 */
    static final int DEFAULT_BATCH_SIZE = 1_000;

    private static final Logger log = LoggerFactory.getLogger(DiagnosticRetentionService.class);

    private static final String DELETE_CALLS_BY_AGE = """
            delete from ai_logical_call
            where id in (
                select id
                from ai_logical_call
                where started_at < ?
                order by started_at, id
                limit ?
            )
            """;

    private static final String DELETE_CALLS_BY_COUNT = """
            delete from ai_logical_call
            where id in (
                select id
                from ai_logical_call
                order by started_at desc, id desc
                offset ?
                limit ?
            )
            """;

    private static final String DELETE_TRANSITIONS_BY_AGE = """
            delete from ai_breaker_transition
            where id in (
                select id
                from ai_breaker_transition
                where transitioned_at < ?
                order by transitioned_at, id
                limit ?
            )
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final int maxAgeDays;
    private final int maxLogicalCalls;
    private final int batchSize;
    private final boolean enabled;

    /** 两个 retention 键由工单 05 引入并定义在 application.properties，这里复用同一份，不另写默认值。 */
    @Autowired
    DiagnosticRetentionService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            @Value("${app.ai.diagnostics.retention.max-age-days:7}") int maxAgeDays,
            @Value("${app.ai.diagnostics.retention.max-logical-calls:10000}") int maxLogicalCalls,
            @Value("${app.ai.diagnostics.retention.enabled:true}") boolean enabled) {
        this(jdbc, transactionManager, Clock.systemUTC(),
                maxAgeDays, maxLogicalCalls, DEFAULT_BATCH_SIZE, enabled);
    }

    public DiagnosticRetentionService(
            JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager,
            Clock clock,
            int maxAgeDays,
            int maxLogicalCalls,
            int batchSize,
            boolean enabled) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transactions = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.clock = Objects.requireNonNull(clock);
        if (maxAgeDays <= 0 || maxLogicalCalls < 0 || batchSize <= 0) {
            throw new IllegalArgumentException(
                    "retention maxAgeDays/batchSize must be positive and maxLogicalCalls non-negative");
        }
        this.maxAgeDays = maxAgeDays;
        this.maxLogicalCalls = maxLogicalCalls;
        this.batchSize = batchSize;
        this.enabled = enabled;
    }

    /**
     * 执行一轮清理直到三项都无数据可删。返回值只用于日志与测试断言，不参与任何控制流。
     */
    public RetentionResult runOnce() {
        OffsetDateTime cutoff = OffsetDateTime.now(clock).minusDays(maxAgeDays);
        Purge byAge = purge(DELETE_CALLS_BY_AGE, cutoff, batchSize);
        // 先按时间删再按条数删：时间清理过后剩下的才是"最近 N 次"的真实分母。
        Purge byCount = purge(DELETE_CALLS_BY_COUNT, maxLogicalCalls, batchSize);
        Purge transitions = purge(DELETE_TRANSITIONS_BY_AGE, cutoff, batchSize);
        return new RetentionResult(
                byAge.deleted(),
                byCount.deleted(),
                transitions.deleted(),
                byAge.passes() + byCount.passes() + transitions.passes());
    }

    /**
     * 清理失败只告警并等下一轮重试，绝不向上抛：它不能影响任何模型调用。
     *
     * <p>{@code enabled} 默认开启，测试用它关掉——{@code @Scheduled} 在每个 Spring 上下文里都会跑，
     * 而集成测试共用同一个库（ADR 0002 的单实例假设），沿用
     * {@code app.knowledge.ingestion.enabled} 已经建立的做法。
     */
    @Scheduled(fixedDelayString = "${app.ai.diagnostics.retention.interval:1h}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        try {
            RetentionResult result = runOnce();
            if (result.deletedCalls() > 0 || result.deletedTransitions() > 0) {
                log.info("诊断保留清理完成：按时间删除 {} 条、按条数上限删除 {} 条逻辑调用，"
                                + "删除 {} 条 breaker transition，共 {} 个删除事务",
                        result.deletedCallsByAge(), result.deletedCallsByCount(),
                        result.deletedTransitions(), result.passes());
            }
        } catch (RuntimeException failure) {
            log.warn("诊断保留清理失败，下一轮重试：{}", failure.toString());
        }
    }

    private Purge purge(String sql, Object first, Object second) {
        long deleted = 0;
        int passes = 0;
        while (true) {
            passes++;
            Integer removed = transactions.execute(status -> jdbc.update(sql, first, second));
            int batch = removed == null ? 0 : removed;
            deleted += batch;
            if (batch < batchSize) {
                return new Purge(deleted, passes);
            }
        }
    }

    /** {@code passes} 是三项清理发出的删除事务总数，用来证明大批量确实被拆开。 */
    public record RetentionResult(
            long deletedCallsByAge,
            long deletedCallsByCount,
            long deletedTransitions,
            int passes) {

        public long deletedCalls() {
            return deletedCallsByAge + deletedCallsByCount;
        }
    }

    private record Purge(long deleted, int passes) {
    }
}
