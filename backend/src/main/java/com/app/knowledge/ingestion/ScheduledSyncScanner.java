package com.app.knowledge.ingestion;

import com.app.knowledge.model.IngestionTriggerSource;
import com.app.knowledge.model.SyncCandidate;
import com.app.knowledge.repository.IngestionRunRepository;
import com.app.knowledge.repository.SourceDocumentRepository;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * URL 来源文档的定时同步（architecture.md §3.3）。
 *
 * <p>核心不是"定时跑"，而是**先判断值不值得刷新再决定要不要真正处理**。每次触发都无脑重新
 * 下载、解析、向量化，会白花大量 Embedding 费用——而定时同步最常见的结果恰恰是"内容没变"。
 *
 * <p>两级变更检测串联：
 * <ol>
 *   <li><b>HEAD 比 ETag / Last-Modified</b>——便宜但不可靠（很多服务器不返回 ETag，
 *       或 Last-Modified 精度只到秒甚至每次都变）。</li>
 *   <li><b>下载后比 SHA-256 内容哈希</b>——绝对可靠，但要付一次完整下载的代价。</li>
 * </ol>
 * 效果是：服务器规范时省下整次下载，不规范时也不会漏更新或误更新。
 *
 * <p>确认变化后**走的是工单 10 那条完全相同的执行链路**（CAS 抢占 + 插入
 * {@code trigger_source='SCHEDULED'} 的任务），没有第二套处理逻辑。定时同步与手动触发的
 * 互斥也因此由同一个 CAS 保证——两边抢同一行的同一个状态字段，先到先得，**不需要任何额外的锁**
 * （ADR 0002）。
 */
@Component
@ConditionalOnProperty(name = "app.knowledge.sync.enabled", havingValue = "true", matchIfMissing = true)
public class ScheduledSyncScanner {

    private static final Logger LOGGER = LoggerFactory.getLogger(ScheduledSyncScanner.class);

    /** 一轮最多处理几篇。积压会在下一轮继续消化。 */
    private static final int BATCH = 20;

    private final SourceDocumentRepository documents;
    private final IngestionRunRepository runs;
    private final RemoteFetcher fetcher;
    private final SyncCronValidator cronValidator;
    private final S3Client s3;
    private final String bucket;
    private final DataSize maxFileSize;

    public ScheduledSyncScanner(SourceDocumentRepository documents, IngestionRunRepository runs,
            RemoteFetcher fetcher, SyncCronValidator cronValidator, S3Client s3,
            @Value("${app.storage.bucket}") String bucket,
            @Value("${spring.servlet.multipart.max-file-size:50MB}") DataSize maxFileSize) {
        this.documents = documents;
        this.runs = runs;
        this.fetcher = fetcher;
        this.cronValidator = cronValidator;
        this.s3 = s3;
        this.bucket = bucket;
        this.maxFileSize = maxFileSize;
    }

    @Scheduled(fixedDelayString = "${app.knowledge.sync.scan-interval:60s}")
    public void scan() {
        try {
            List<SyncCandidate> due = documents.findDueForSync(BATCH);
            for (SyncCandidate document : due) {
                try {
                    syncOne(document);
                } catch (Exception exception) {
                    // 单篇失败不能中断整轮扫描，否则一个坏链接会把所有同步都卡住
                    LOGGER.warn("文档 {} 定时同步失败：{}", document.id(), exception.toString());
                    recordSkippedOrFailed(document, "同步检查失败：" + exception.getMessage(), false);
                }
            }
        } catch (Exception exception) {
            LOGGER.warn("定时同步扫描失败：{}", exception.toString());
        }
    }

    private void syncOne(SyncCandidate document) {
        // 第一级：HEAD。拿不到头（不支持 HEAD、405、超时）时 empty，退到第二级而不是判失败。
        Optional<RemoteFetcher.Head> head = fetcher.head(document.sourceUri());
        if (head.isPresent() && unchangedByHeaders(document, head.get())) {
            recordSkippedOrFailed(document, "远程文件的 ETag / Last-Modified 未变化。", true);
            return;
        }

        // 第二级：下载后比内容哈希。HEAD 说变了也可能是假警报（Last-Modified 每次都变的服务器），
        // 所以这一级不是可选的补充，而是最终判据。
        RemoteFetcher.Fetched fetched = fetcher.fetch(document.sourceUri(), maxFileSize.toBytes());
        try {
            String hash = ContentHash.sha256OfFile(fetched.file());
            if (Objects.equals(hash, document.contentHash())) {
                // 顺手把这次拿到的头存下来：下次 HEAD 就可能在第一级命中，省掉一次完整下载
                documents.updateSyncMeta(document.id(), fetched.etag(), fetched.lastModified(), null);
                recordSkippedOrFailed(document, "远程文件内容未发生变化。", true);
                return;
            }

            // 确认变化：新内容覆盖对象存储里的副本，之后走的就是与 FILE 来源完全相同的链路
            s3.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(document.fileKey())
                    .contentType(fetched.contentType())
                    .build(), RequestBody.fromFile(fetched.file()));
            documents.updateSyncMeta(document.id(), fetched.etag(), fetched.lastModified(), hash);

            // CAS 抢占。抢不到说明手动触发刚好先到一步——那就让它去做，本次同步什么都不用干。
            if (documents.claimForProcessing(document.id())) {
                runs.insertQueued(document.kbId(), document.id(), IngestionTriggerSource.SCHEDULED);
                LOGGER.info("文档 {} 内容已变化，定时同步触发重新处理", document.id());
            } else {
                LOGGER.info("文档 {} 已在处理中，本次定时同步跳过抢占", document.id());
            }
            advanceSchedule(document);
        } finally {
            deleteQuietly(fetched.file());
        }
    }

    private boolean unchangedByHeaders(SyncCandidate document, RemoteFetcher.Head head) {
        // 只有当库里存过对应的头、且这次拿到的完全一致，才敢判定"没变"。
        // 任一侧为空都说明这一级没有可比的依据，必须退到内容哈希。
        boolean etagSame = head.etag() != null && head.etag().equals(document.httpEtag());
        boolean modifiedSame = head.lastModified() != null
                && head.lastModified().equals(document.httpLastModified());
        return etagSame || modifiedSame;
    }

    /**
     * 记录一次检查结果并推进调度。
     *
     * <p>**`SKIPPED` 必须落库**（PRD §4.3）：没有它，用户在界面上看到"定时同步开着但从来没有
     * 执行记录"，无法区分"检查过没变化"和"调度根本没跑"。而"检查过没变化"恰恰是定时同步
     * 正常工作时最常见的结果。
     */
    private void recordSkippedOrFailed(SyncCandidate document, String message, boolean skipped) {
        long runId = runs.insertQueued(document.kbId(), document.id(), IngestionTriggerSource.SCHEDULED);
        if (skipped) {
            runs.markSkipped(runId, message);
        } else {
            runs.markFailed(runId, message);
        }
        advanceSchedule(document);
    }

    /** 每次检查（含跳过、含失败）都要推进，否则这篇文档会在每一轮扫描里被反复命中。 */
    private void advanceSchedule(SyncCandidate document) {
        OffsetDateTime next = null;
        try {
            next = OffsetDateTime.from(
                    cronValidator.validate(document.syncCron()).next(ZonedDateTime.now()));
        } catch (SyncCronValidator.InvalidCronException invalid) {
            // 库里存着一个已经不合法的表达式（配置写入之后 min-interval 被调大了，等等）。
            // next_sync_time 置空让它退出扫描范围，而不是每 60s 重试一次注定失败的解析。
            LOGGER.warn("文档 {} 的同步规则不再合法（{}），已停止调度", document.id(), invalid.getMessage());
        }
        documents.advanceSync(document.id(), next);
    }

    private void deleteQuietly(Path temp) {
        try {
            Files.deleteIfExists(temp);
        } catch (Exception exception) {
            LOGGER.warn("临时文件删除失败，需要人工清理：{}", temp);
        }
    }
}
