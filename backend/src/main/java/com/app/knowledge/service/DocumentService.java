package com.app.knowledge.service;

import com.app.knowledge.ingestion.TextExtractor;
import com.app.knowledge.model.ChunkConfig;
import com.app.knowledge.model.DocumentDetail;
import com.app.knowledge.model.ChunkStrategy;
import com.app.knowledge.model.DocumentStatus;
import com.app.knowledge.model.SourceDocument;
import com.app.knowledge.repository.IngestionRunRepository;
import com.app.knowledge.repository.KnowledgeBaseRepository;
import com.app.knowledge.repository.SourceDocumentRepository;
import com.app.knowledge.web.ApiException;
import com.app.knowledge.web.PageResponse;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** 文档上传与列表。 */
@Service
public class DocumentService {

    private static final Logger LOGGER = LoggerFactory.getLogger(DocumentService.class);

    private final SourceDocumentRepository documents;
    private final KnowledgeBaseRepository knowledgeBases;
    private final IngestionRunRepository runs;
    private final S3Client s3;
    private final String bucket;

    public DocumentService(SourceDocumentRepository documents, KnowledgeBaseRepository knowledgeBases,
            IngestionRunRepository runs, S3Client s3, @Value("${app.storage.bucket}") String bucket) {
        this.documents = documents;
        this.knowledgeBases = knowledgeBases;
        this.runs = runs;
        this.s3 = s3;
        this.bucket = bucket;
    }

    /**
     * 上传本地文件。
     *
     * <p>**方法上没有 {@code @Transactional}，这是有意的**：唯一的数据库操作是最后一条
     * INSERT，它本身就是原子的；前面是落盘和对象存储上传两段耗时 IO，包进事务只会让一个
     * 数据库连接被占用整个上传时长。这条纪律在学习笔记里出现过五次。
     *
     * <p>上传后文档是 {@code PENDING}，**不触发分块**（PRD §7 决策 1）。界面必须明确
     * 展示"待处理"，否则用户会以为已经能检索了。
     */
    public SourceDocument uploadFile(long kbId, MultipartFile file, String name,
            ChunkStrategy strategy, Integer chunkSize, Integer overlap) {
        requireKnowledgeBase(kbId);

        String originalName = file.getOriginalFilename();
        if (originalName == null || originalName.isBlank()) {
            throw ApiException.invalidRequest("上传的文件缺少文件名。");
        }
        // 白名单在入口就拦，不等到分块阶段——那时文件已经传完、任务已经建好，
        // 用户要等到处理失败才知道格式不行。
        if (!TextExtractor.isSupported(originalName)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE",
                    "不支持这种文件格式。目前支持 .txt、.md、.pdf、.docx。");
        }
        ChunkConfig config = resolveChunkConfig(strategy, chunkSize, overlap);

        String extension = originalName.substring(originalName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String fileKey = "knowledge-base/%d/%s.%s".formatted(kbId, UUID.randomUUID(), extension);

        File temp = null;
        try {
            temp = Files.createTempFile("xrag-upload-", "." + extension).toFile();
            // 先落盘再上传：RequestBody.fromFile 能从文件长度直接得到 Content-Length、
            // 分块读取算 checksum，不需要把整个文件读进堆。fromBytes / fromInputStream
            // 做不到（流不可重放，SDK 只能先全部缓冲），那正是学习笔记 03-03 的内存放大根因。
            file.transferTo(temp);
            s3.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(fileKey)
                    .contentType(file.getContentType())
                    .build(), RequestBody.fromFile(temp));
        } catch (IOException | RuntimeException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_FAILED",
                    "文件保存失败，请稍后重试。");
        } finally {
            deleteQuietly(temp);
        }

        long id = documents.insertFile(kbId, name == null || name.isBlank() ? originalName : name.trim(),
                fileKey, file.getSize(), file.getContentType(),
                config.strategy(), config.chunkSize(), config.overlap());
        return documents.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public PageResponse<SourceDocument> list(long kbId, DocumentStatus status, Boolean enabled,
            Integer page, Integer size) {
        requireKnowledgeBase(kbId);
        int normalizedPage = PageResponse.normalizePage(page);
        int normalizedSize = PageResponse.normalizeSize(size);
        List<SourceDocument> items = documents.findPage(kbId, status, enabled, normalizedPage, normalizedSize);
        return new PageResponse<>(items, normalizedPage, normalizedSize, documents.count(kbId, status, enabled));
    }

    @Transactional(readOnly = true)
    public DocumentDetail get(long docId) {
        SourceDocument document = documents.findById(docId)
                .orElseThrow(() -> ApiException.notFound("文档不存在或已被删除。"));
        return new DocumentDetail(document, runs.findLatestByDocId(docId).orElse(null));
    }

    /** 缺省值来自 api.md §3：策略 RECURSIVE、chunkSize 1000、overlap 100。 */
    private ChunkConfig resolveChunkConfig(ChunkStrategy strategy, Integer chunkSize, Integer overlap) {
        try {
            return ChunkConfig.of(
                    strategy == null ? ChunkStrategy.RECURSIVE : strategy,
                    chunkSize == null ? ChunkConfig.DEFAULT_CHUNK_SIZE : chunkSize,
                    overlap == null ? ChunkConfig.DEFAULT_OVERLAP : overlap);
        } catch (IllegalArgumentException invalid) {
            throw ApiException.invalidRequest(invalid.getMessage());
        }
    }

    private void requireKnowledgeBase(long kbId) {
        if (knowledgeBases.findById(kbId).isEmpty()) {
            throw ApiException.notFound("知识库不存在或已被删除。");
        }
    }

    /**
     * 临时文件在成功和失败两条路径上都要删。删失败只记警告——此时业务已经完成（或已经
     * 在抛别的异常），为一个残留的临时文件再抛一个异常会把真正的原因盖掉。
     */
    private void deleteQuietly(File temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp.toPath());
        } catch (IOException exception) {
            LOGGER.warn("临时文件删除失败，需要人工清理：{}", temp.getAbsolutePath(), exception);
        }
    }
}
