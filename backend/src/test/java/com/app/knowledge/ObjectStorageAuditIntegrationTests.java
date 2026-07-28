package com.app.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.knowledge.storage.ObjectStorageAuditService;
import com.app.knowledge.storage.ObjectStorageMaintenance;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** 对象存储运维审计的公开 Service 接缝。 */
@SpringBootTest
@Import(FakeEmbeddingConfig.class)
@Transactional
class ObjectStorageAuditIntegrationTests {

    @Autowired
    private ObjectStorageAuditService auditService;

    @Autowired
    private ObjectStorageMaintenance maintenance;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private S3Client s3;

    @Value("${app.storage.bucket}")
    private String bucket;

    @Test
    void dryRun只报告数据库未引用的对象() {
        prepareReferencedAndOrphan();

        ObjectStorageAuditService.Report report = auditService.audit(Duration.ZERO);

        assertThat(report.scannedObjects()).isEqualTo(2);
        assertThat(report.referencedObjects()).isEqualTo(1);
        assertThat(report.orphans()).extracting(ObjectStorageAuditService.Orphan::key)
                .containsExactly("knowledge-bases/audit/orphan.txt");
    }

    @Test
    void 永久清理只删除超过宽限期的孤儿对象() {
        prepareReferencedAndOrphan();

        ObjectStorageAuditService.PurgeResult result = auditService.purge(Duration.ZERO);

        assertThat(result.deletedObjects()).isEqualTo(1);
        assertThat(s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
                .contents().stream()
                .filter(object -> object.key().startsWith("knowledge-base"))
                .toList()).extracting(object -> object.key())
                .containsExactly("knowledge-bases/audit/referenced.txt");
    }

    @Test
    void 默认维护任务只审计不执行永久删除() {
        prepareReferencedAndOrphan();

        ObjectStorageMaintenance.RunResult result = maintenance.runOnce();

        assertThat(result.orphanObjects()).isEqualTo(1);
        assertThat(result.deletedObjects()).isZero();
        assertThat(s3.listObjectsV2(ListObjectsV2Request.builder().bucket(bucket).build())
                .contents()).hasSize(2);
    }

    @Test
    void 逻辑删除文档只在宽限期内继续保留原文件() {
        prepareReferencedAndOrphan();
        jdbc.update("""
                update source_document
                   set deleted = true, delete_time = now()
                 where file_key = 'knowledge-bases/audit/referenced.txt'
                """);

        ObjectStorageAuditService.Report report = auditService.audit(Duration.ofDays(7));

        assertThat(report.referencedObjects()).isEqualTo(1);
        assertThat(report.orphans()).extracting(ObjectStorageAuditService.Orphan::key)
                .doesNotContain("knowledge-bases/audit/referenced.txt");
    }

    @Test
    void 逻辑删除文档超过宽限期后原文件进入孤儿清单() {
        prepareReferencedAndOrphan();
        jdbc.update("""
                update source_document
                   set deleted = true, delete_time = now() - interval '1 day'
                 where file_key = 'knowledge-bases/audit/referenced.txt'
                """);

        ObjectStorageAuditService.Report report = auditService.audit(Duration.ZERO);

        assertThat(report.referencedObjects()).isZero();
        assertThat(report.orphans()).extracting(ObjectStorageAuditService.Orphan::key)
                .contains("knowledge-bases/audit/referenced.txt");
    }

    @Test
    void 审计忽略业务Bucket中不属于知识库模块的对象() {
        ObjectStorageAuditService.Report before = auditService.audit(Duration.ZERO);
        put("other-module/config.json");

        ObjectStorageAuditService.Report after = auditService.audit(Duration.ZERO);

        assertThat(after.scannedObjects()).isEqualTo(before.scannedObjects());
        assertThat(after.orphans()).extracting(ObjectStorageAuditService.Orphan::key)
                .doesNotContain("other-module/config.json");
    }

    private void prepareReferencedAndOrphan() {
        long kbId = jdbc.queryForObject("""
                insert into knowledge_base
                    (name, storage_alias, embedding_model, embedding_dimensions)
                values ('审计测试知识库', '审计测试知识库', 'test', 1024)
                returning id
                """, Long.class);
        String referencedKey = "knowledge-bases/audit/referenced.txt";
        jdbc.update("""
                insert into source_document
                    (kb_id, name, source_type, file_key, storage_object_id)
                values (?, 'referenced.txt', 'FILE', ?, 'audit-referenced')
                """, kbId, referencedKey);
        put(referencedKey);
        put("knowledge-bases/audit/orphan.txt");
    }

    private void put(String key) {
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).build(),
                RequestBody.fromBytes("test".getBytes(StandardCharsets.UTF_8)));
    }
}
