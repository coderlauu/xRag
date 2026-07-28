package com.app.knowledge.storage;

import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 定期输出对象审计结果；只有显式开关开启时才执行永久清理。 */
@Component
public class ObjectStorageMaintenance {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObjectStorageMaintenance.class);

    public record RunResult(long scannedObjects, long orphanObjects, long deletedObjects) {}

    private final ObjectStorageAuditService auditService;
    private final Duration gracePeriod;
    private final boolean cleanupEnabled;

    public ObjectStorageMaintenance(ObjectStorageAuditService auditService,
            @Value("${app.storage.maintenance.grace-period:7d}") Duration gracePeriod,
            @Value("${app.storage.maintenance.cleanup-enabled:false}") boolean cleanupEnabled) {
        this.auditService = auditService;
        this.gracePeriod = gracePeriod;
        this.cleanupEnabled = cleanupEnabled;
    }

    public RunResult runOnce() {
        if (cleanupEnabled) {
            ObjectStorageAuditService.PurgeResult result = auditService.purge(gracePeriod);
            LOGGER.info("对象存储维护完成：扫描 {} 个对象，永久清理 {} 个、共 {} 字节",
                    result.scannedObjects(), result.deletedObjects(), result.deletedBytes());
            return new RunResult(result.scannedObjects(), result.deletedObjects(), result.deletedObjects());
        }

        ObjectStorageAuditService.Report report = auditService.audit(gracePeriod);
        LOGGER.info("对象存储审计完成：扫描 {} 个对象，引用中 {} 个，发现 {} 个超过宽限期的孤儿对象；"
                        + "永久清理开关未开启",
                report.scannedObjects(), report.referencedObjects(), report.orphans().size());
        return new RunResult(report.scannedObjects(), report.orphans().size(), 0);
    }

    @Scheduled(fixedDelayString = "${app.storage.maintenance.interval:24h}")
    public void scheduled() {
        try {
            runOnce();
        } catch (Exception failure) {
            LOGGER.warn("对象存储维护失败：{}", failure.toString());
        }
    }
}
