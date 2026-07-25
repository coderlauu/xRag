package com.app.knowledge.ingestion;

import com.app.knowledge.repository.IngestionRunRepository;
import com.app.knowledge.repository.SourceDocumentRepository;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 把 {@code QUEUED} 的入库任务派发到线程池执行，并维护心跳。
 *
 * <p>轮询 + CAS 就是这个模块的"消息队列"（ADR 0002）。轮询有 2s 的固有延迟，
 * 这对一个耗时几十秒的任务来说无关紧要，换来的是不引入 MQ 这一整套运维负担。
 *
 * <p>{@code app.knowledge.ingestion.enabled=false} 可以整个关掉本组件。这个开关不是
 * 为了配置而配置——**多个 Spring 上下文同时连同一个库时，每个上下文的调度器都会去抢
 * 任务，而各自的启动回收又会把别人正在跑的任务判成僵尸**。测试里 `ApplicationTests`
 * 与集成测试的配置不同、注定分属两个上下文，就必须靠它把前者的调度器关掉。这同时也是
 * ADR 0002「启动回收依赖单实例假设」在真实环境里会怎样失效的一个现成例子。
 */
@Component
@ConditionalOnProperty(name = "app.knowledge.ingestion.enabled", havingValue = "true", matchIfMissing = true)
public class IngestionDispatcher implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionDispatcher.class);

    /** 一轮最多派发几条。留有余量即可——积压会在下一轮继续消化。 */
    private static final int BATCH = 5;
    private static final int WORKERS = 2;
    private static final long HEARTBEAT_SECONDS = 10;

    private final IngestionRunRepository runs;
    private final SourceDocumentRepository documents;
    private final IngestionExecutor executor;
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKERS);
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();
    private final Map<Long, ScheduledFuture<?>> running = new ConcurrentHashMap<>();

    public IngestionDispatcher(IngestionRunRepository runs, SourceDocumentRepository documents,
            IngestionExecutor executor) {
        this.runs = runs;
        this.documents = documents;
        this.executor = executor;
    }

    /**
     * 启动时回收僵尸任务。
     *
     * <p>**这一层的正确性完全依赖单实例假设**（ADR 0002）：进程刚起来时不可能存在真正在跑的
     * 任务，所以把所有 `RUNNING` 判成僵尸是完全准确的、没有误伤。多实例部署前必须先改掉，
     * 否则实例 A 启动会把实例 B 正在跑的任务误杀。
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            List<Long> stuck = documents.findRunningIds();
            for (Long docId : stuck) {
                documents.markFailed(docId, "上一次处理因应用重启而中断，请重新触发。");
                runs.findLatestByDocId(docId).ifPresent(
                        run -> runs.markFailed(run.id(), "上一次处理因应用重启而中断。"));
            }
            if (!stuck.isEmpty()) {
                LOGGER.info("启动回收：{} 篇文档从 RUNNING 重置为 FAILED", stuck.size());
            }
        } catch (Exception exception) {
            // 数据库此刻不可达是正常情况，沿用既有的"不阻塞启动"模式
            LOGGER.warn("启动回收未能执行：{}", exception.toString());
        }
    }

    @Scheduled(fixedDelayString = "${app.knowledge.ingestion.poll-interval:2s}")
    public void dispatch() {
        try {
            for (Long runId : runs.findQueuedIds(BATCH)) {
                if (runs.claim(runId)) {
                    submit(runId);
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("入库任务派发轮询失败：{}", exception.toString());
        }
    }

    private void submit(long runId) {
        // 心跳独立于执行线程：执行线程正卡在下载或 Embedding 调用上时它也得继续跳，
        // 否则心跳超时回收会把一个其实还活着的任务误判为卡死。
        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(
                () -> safeHeartbeat(runId), HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
        running.put(runId, heartbeat);
        workers.submit(() -> {
            try {
                executor.execute(runId);
            } finally {
                ScheduledFuture<?> future = running.remove(runId);
                if (future != null) {
                    future.cancel(false);
                }
            }
        });
    }

    private void safeHeartbeat(long runId) {
        try {
            executor.heartbeat(runId);
        } catch (Exception exception) {
            LOGGER.warn("心跳更新失败 run={}：{}", runId, exception.toString());
        }
    }
}
