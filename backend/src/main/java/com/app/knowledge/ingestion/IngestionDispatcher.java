package com.app.knowledge.ingestion;

import com.app.knowledge.model.StaleRun;
import com.app.knowledge.repository.IngestionRunRepository;
import com.app.knowledge.repository.SourceDocumentRepository;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
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
import org.springframework.beans.factory.annotation.Value;
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
    private final Duration heartbeatTimeout;
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKERS);
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();
    private final Map<Long, ScheduledFuture<?>> running = new ConcurrentHashMap<>();

    public IngestionDispatcher(IngestionRunRepository runs, SourceDocumentRepository documents,
            IngestionExecutor executor,
            @Value("${app.knowledge.ingestion.heartbeat-timeout:5m}") Duration heartbeatTimeout) {
        this.runs = runs;
        this.documents = documents;
        this.executor = executor;
        this.heartbeatTimeout = heartbeatTimeout;
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

    /**
     * 第二层回收：心跳超时。
     *
     * <p>启动回收只覆盖"进程重启"这一种中断方式，**任务在进程还活着的时候卡死**（执行线程
     * 挂在一个没有超时的网络调用上、或者被死锁住）它管不着。没有这一层，那份文档会永久停在
     * `RUNNING`：CAS 永远抢不到、删除和更新也都被 409 挡住，用户什么都做不了。
     *
     * <p>**与启动回收不同，这一层不依赖单实例假设**：判据是这条任务自己的心跳停了，而不是
     * "进程刚起来所以不可能有任务在跑"。多实例下它依然成立。
     *
     * <p>心跳由 {@link #submit} 里独立的调度线程每 10s 更新一次，独立于执行线程——执行线程
     * 正卡在下载或 Embedding 上时心跳也得继续跳，否则这里会把还活着的任务误判成卡死。
     */
    @Scheduled(fixedDelayString = "${app.knowledge.ingestion.recovery-interval:60s}")
    public void recoverStale() {
        try {
            for (StaleRun stale : runs.findStale(heartbeatTimeout)) {
                String reason = describe(stale);
                // 两张表都要改：只改 run 会让文档永久卡在 RUNNING，用户既看不到原因也重试不了
                runs.markStaleFailed(stale.id(), reason);
                documents.markFailed(stale.docId(), reason);
                LOGGER.warn("心跳超时回收：任务 {}（文档 {}）标记为 FAILED，心跳存活 {}s，最后步骤 {}",
                        stale.id(), stale.docId(), stale.aliveSeconds(), stale.phase());
            }
        } catch (Exception exception) {
            LOGGER.warn("心跳超时回收失败：{}", exception.toString());
        }
    }

    /**
     * 兜底回收是**唯一一种拿不到真实失败原因的失败**——它只知道"心跳没了"。所以这条消息
     * 必须尽力把用户引向正确的排查方向，而不是丢下一句"卡死"。
     *
     * <p>判据是心跳存活了多久：心跳每 {@value #HEARTBEAT_SECONDS} 秒一跳，若任务开始后
     * 连三跳都没跳满就断了，几乎必然是**进程整个消失了**（被停止、重启、或被 kill），
     * 而不是某一步执行得慢。这两种原因的处置完全不同，混成一句话说等于什么都没说。
     *
     * <p>措辞上刻意用"最后记录到的步骤是 X"而不是"X 失败了"：进程被杀时执行线程可能早已
     * 走到下一步，只是没来得及写库。说成"切分失败"会让用户去查一个根本没出错的功能。
     */
    private String describe(StaleRun stale) {
        String step = stale.phase() == null
                ? "当时尚未开始任何步骤"
                : "最后记录到的步骤是 " + stale.phase();
        if (stale.aliveSeconds() >= 0 && stale.aliveSeconds() < HEARTBEAT_SECONDS * 3) {
            return ("处理开始 %d 秒后就完全失去响应（%s），超过 %s 无心跳，已中止。"
                    + "这通常意味着应用进程在处理过程中被停止或重启，而不是某一步出错。请重新触发。")
                    .formatted(stale.aliveSeconds(), step, heartbeatTimeout);
        }
        return "处理超过 %s 没有任何进展（%s），已判定为卡死并中止，请重新触发。"
                .formatted(heartbeatTimeout, step);
    }

    /**
     * 优雅停机时把本进程正在跑的任务标记掉。
     *
     * <p>没有这一层，正常的一次「停掉应用」会让任务在库里躺满 {@code heartbeatTimeout}
     * 才被兜底回收，而那条兜底消息只能猜原因。这里在**关闭的当下**就知道确切原因，
     * 写下来比五分钟后再猜准确得多，用户也不用干等。
     *
     * <p>只能覆盖收到关闭信号的情况；{@code kill -9} 仍然只能靠启动回收与心跳超时兜底。
     */
    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
        heartbeats.shutdownNow();
        for (Long runId : List.copyOf(running.keySet())) {
            try {
                String reason = "应用在处理过程中关闭，本次处理已中断，请重新触发。";
                runs.markFailed(runId, reason);
                runs.findById(runId).ifPresent(run -> documents.markFailed(run.docId(), reason));
            } catch (Exception exception) {
                LOGGER.warn("关闭时标记任务 {} 失败：{}", runId, exception.toString());
            }
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
