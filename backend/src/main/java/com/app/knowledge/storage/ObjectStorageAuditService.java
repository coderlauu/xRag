package com.app.knowledge.storage;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

/**
 * 对象存储审计：识别不再被活动业务记录或任务引用、且已过宽限期的对象。
 *
 * <p>逻辑删除文档的原文件在宽限期内仍视为引用，保留一个可恢复窗口；过期后才进入孤儿清单。
 */
@Service
public class ObjectStorageAuditService {

    private static final List<String> MANAGED_PREFIXES = List.of(
            "knowledge-base/",   // V5 前的历史对象
            "knowledge-bases/"); // V5 起的新对象

    public record Orphan(String key, long size, Instant lastModified) {}

    public record Report(long scannedObjects, long referencedObjects, List<Orphan> orphans) {}

    public record PurgeResult(long scannedObjects, long deletedObjects, long deletedBytes) {}

    private final JdbcTemplate jdbc;
    private final S3Client s3;
    private final String bucket;

    public ObjectStorageAuditService(JdbcTemplate jdbc, S3Client s3,
            @Value("${app.storage.bucket}") String bucket) {
        this.jdbc = jdbc;
        this.s3 = s3;
        this.bucket = bucket;
    }

    public Report audit(Duration gracePeriod) {
        if (gracePeriod.isNegative()) {
            throw new IllegalArgumentException("对象审计宽限期不能为负数");
        }
        Instant cutoff = Instant.now().minus(gracePeriod);
        Set<String> referencedKeys = new HashSet<>(jdbc.queryForList("""
                select file_key
                  from source_document
                 where file_key is not null
                   and (deleted = false or delete_time is null or delete_time > ?)
                union
                select input_file_key
                  from ingestion_run
                 where status in ('QUEUED', 'RUNNING') and input_file_key is not null
                """, String.class, Timestamp.from(cutoff)));
        List<Orphan> orphans = new ArrayList<>();
        long scanned = 0;
        long referenced = 0;
        for (String prefix : MANAGED_PREFIXES) {
            String token = null;
            do {
                var page = s3.listObjectsV2(ListObjectsV2Request.builder()
                        .bucket(bucket)
                        .prefix(prefix)
                        .continuationToken(token)
                        .build());
                for (var object : page.contents()) {
                    scanned++;
                    if (referencedKeys.contains(object.key())) {
                        referenced++;
                    } else if (!object.lastModified().isAfter(cutoff)) {
                        orphans.add(new Orphan(object.key(), object.size(), object.lastModified()));
                    }
                }
                token = page.isTruncated() ? page.nextContinuationToken() : null;
            } while (token != null);
        }
        return new Report(scanned, referenced, List.copyOf(orphans));
    }

    /**
     * 显式永久清理。调用方必须传入保留宽限期；默认定时任务是否调用本方法由独立开关控制。
     */
    public PurgeResult purge(Duration gracePeriod) {
        Report report = audit(gracePeriod);
        long deletedBytes = 0;
        for (Orphan orphan : report.orphans()) {
            s3.deleteObject(request -> request.bucket(bucket).key(orphan.key()));
            deletedBytes += orphan.size();
        }
        return new PurgeResult(report.scannedObjects(), report.orphans().size(), deletedBytes);
    }
}
