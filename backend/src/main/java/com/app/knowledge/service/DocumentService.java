package com.app.knowledge.service;

import com.app.knowledge.embedding.EmbeddingClient;
import com.app.knowledge.ingestion.ContentHash;
import com.app.knowledge.ingestion.RemoteFetcher;
import com.app.knowledge.ingestion.SyncCronValidator;
import com.app.knowledge.ingestion.TextExtractor;
import com.app.knowledge.model.ChunkConfig;
import com.app.knowledge.model.DocumentChunk;
import com.app.knowledge.model.DocumentDetail;
import com.app.knowledge.model.DocumentUpdateResult;
import com.app.knowledge.model.ChunkStrategy;
import com.app.knowledge.model.DocumentStatus;
import com.app.knowledge.model.SourceDocument;
import com.app.knowledge.model.SourceType;
import com.app.knowledge.repository.DocumentChunkRepository;
import com.app.knowledge.repository.IngestionRunRepository;
import com.app.knowledge.repository.KnowledgeBaseRepository;
import com.app.knowledge.repository.SourceDocumentRepository;
import com.app.knowledge.vector.ChunkVectorRepository;
import com.app.knowledge.web.ApiException;
import com.app.knowledge.web.PageResponse;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.unit.DataSize;
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
    private final DocumentChunkRepository chunks;
    private final ChunkVectorRepository vectors;
    private final EmbeddingClient embeddingClient;
    private final TransactionOperations transactions;
    private final RemoteFetcher fetcher;
    private final SyncCronValidator cronValidator;
    private final DataSize maxFileSize;
    private final S3Client s3;
    private final String bucket;

    public DocumentService(SourceDocumentRepository documents, KnowledgeBaseRepository knowledgeBases,
            IngestionRunRepository runs, DocumentChunkRepository chunks, ChunkVectorRepository vectors,
            EmbeddingClient embeddingClient, PlatformTransactionManager transactionManager,
            RemoteFetcher fetcher, SyncCronValidator cronValidator,
            @Value("${spring.servlet.multipart.max-file-size:50MB}") DataSize maxFileSize,
            S3Client s3, @Value("${app.storage.bucket}") String bucket) {
        this.fetcher = fetcher;
        this.cronValidator = cronValidator;
        this.maxFileSize = maxFileSize;
        this.documents = documents;
        this.knowledgeBases = knowledgeBases;
        this.runs = runs;
        this.chunks = chunks;
        this.vectors = vectors;
        this.embeddingClient = embeddingClient;
        this.transactions = new TransactionTemplate(transactionManager);
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

    /**
     * 添加 URL 来源文档（api.md §3）。
     *
     * <p>**创建时就同步抓取一次存进对象存储**，和 FILE 来源对齐：之后的重新分块直接读对象
     * 存储里的副本，不必再下一次，也就与本地上传共用了完全同一条执行链路。
     *
     * <p>**抓取失败一律 `400` 且不落库**——留下一条永远处理不了的记录，用户只能困惑地反复
     * 点"重试"。方法上没有 {@code @Transactional}：唯一的数据库写入是最后那条 INSERT，
     * 前面是网络抓取和对象存储上传两段耗时 IO。
     */
    public SourceDocument addUrl(long kbId, String sourceUri, String name, ChunkStrategy strategy,
            Integer chunkSize, Integer overlap, Boolean syncEnabled, String syncCron) {
        requireKnowledgeBase(kbId);
        ChunkConfig config = resolveChunkConfig(strategy, chunkSize, overlap);

        boolean sync = Boolean.TRUE.equals(syncEnabled);
        OffsetDateTime nextSyncTime = null;
        if (sync) {
            try {
                nextSyncTime = OffsetDateTime.from(cronValidator.validate(syncCron).next(ZonedDateTime.now()));
            } catch (SyncCronValidator.InvalidCronException invalid) {
                throw ApiException.invalidRequest(invalid.getMessage());
            }
        }

        RemoteFetcher.Fetched fetched;
        try {
            fetched = fetcher.fetch(sourceUri, maxFileSize.toBytes());
        } catch (RemoteFetcher.FetchException failure) {
            throw ApiException.invalidRequest(failure.getMessage());
        }

        try {
            String resolvedName = name == null || name.isBlank()
                    ? fileNameFromUri(sourceUri) : name.trim();
            // 白名单按文件名判断，与本地上传同一套规则。HTML 不在白名单里——直链才是这个
            // 功能的用法，网页地址提示由前端负责（api.md §3）。
            if (!TextExtractor.isSupported(resolvedName)) {
                throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE",
                        "不支持这种文件格式。目前支持 .txt、.md、.pdf、.docx。");
            }
            String extension = resolvedName.substring(resolvedName.lastIndexOf('.') + 1)
                    .toLowerCase(Locale.ROOT);
            String fileKey = "knowledge-base/%d/%s.%s".formatted(kbId, UUID.randomUUID(), extension);
            String contentHash = ContentHash.sha256OfFile(fetched.file());

            s3.putObject(PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(fileKey)
                    .contentType(fetched.contentType())
                    .build(), RequestBody.fromFile(fetched.file()));

            long id = documents.insertUrl(kbId, resolvedName, sourceUri.trim(), fileKey,
                    fetched.size(), fetched.contentType(), contentHash, fetched.etag(),
                    fetched.lastModified(), config.strategy(), config.chunkSize(), config.overlap(),
                    sync, sync ? syncCron.trim() : null, nextSyncTime);
            return documents.findById(id).orElseThrow();
        } catch (ApiException known) {
            throw known;
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "UPLOAD_FAILED",
                    "远程文件保存失败，请稍后重试。");
        } finally {
            // 成功与失败两条路径都要清理（抓取自身失败时由 RemoteFetcher 负责删）
            deleteQuietly(fetched.file().toFile());
        }
    }

    /** 从 URL 路径取文件名。取不到就退回一个带扩展名的兜底，让白名单校验能给出准确的提示。 */
    private String fileNameFromUri(String sourceUri) {
        String path = sourceUri.trim();
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        String candidate = slash >= 0 ? path.substring(slash + 1) : path;
        return candidate.isBlank() ? "remote" : candidate;
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

    /**
     * 更新文档。全字段可选，只更新传了的字段（api.md §3）。
     *
     * <p>**改分块参数不会自动重新分块**，只在响应里带 {@code needsRechunk} 让前端引导用户
     * 显式触发（PRD §4.2）。一份几百分块的文档重新分块是一次可观的模型调用费用，而用户
     * 可能只是想改个名字顺手调了下参数。
     */
    @Transactional
    public DocumentUpdateResult update(long docId, String name, ChunkStrategy strategy,
            Integer chunkSize, Integer overlap, String sourceUri, Boolean syncEnabled, String syncCron) {
        SourceDocument document = requireDocument(docId);
        requireNotRunning(document);

        if (name != null && name.isBlank()) {
            throw ApiException.invalidRequest("文档名称不能为空。");
        }
        // 同步三件套只对 URL 来源有意义。数据库的 ck_source_document_sync_fields 会再兜一道，
        // 但那时报出来的是约束名，对用户毫无意义。
        if (document.sourceType() != SourceType.URL
                && (sourceUri != null || syncEnabled != null || syncCron != null)) {
            throw ApiException.invalidRequest("本地上传的文档没有来源地址与定时同步，无法修改这几项。");
        }

        // 校验的是**合并之后**的取值：只传 overlap 时也要和库里现有的 chunkSize 比较，
        // 否则能存进一个 overlap >= chunkSize 的组合，等到下次分块才在算法里炸开。
        ChunkStrategy mergedStrategy = strategy != null ? strategy : document.chunkStrategy();
        int mergedSize = chunkSize != null ? chunkSize : document.chunkConfig().chunkSize();
        int mergedOverlap = overlap != null ? overlap : document.chunkConfig().overlap();
        try {
            ChunkConfig.of(mergedStrategy, mergedSize, mergedOverlap);
        } catch (IllegalArgumentException invalid) {
            throw ApiException.invalidRequest(invalid.getMessage());
        }

        // 同步规则改了就要重新校验并重算下次执行时间，否则新表达式要等到下一次触发才生效，
        // 而 next_sync_time 还停在按旧表达式算出来的那个时刻。
        OffsetDateTime nextSyncTime = null;
        boolean syncOn = syncEnabled != null ? syncEnabled : document.syncEnabled();
        String effectiveCron = syncCron != null ? syncCron : document.syncCron();
        if (syncOn && (syncEnabled != null || syncCron != null)) {
            try {
                nextSyncTime = OffsetDateTime.from(
                        cronValidator.validate(effectiveCron).next(ZonedDateTime.now()));
            } catch (SyncCronValidator.InvalidCronException invalid) {
                throw ApiException.invalidRequest(invalid.getMessage());
            }
        }

        documents.update(docId, name, strategy, chunkSize, overlap, sourceUri, syncEnabled, syncCron);
        if (nextSyncTime != null) {
            documents.advanceNextSyncTime(docId, nextSyncTime);
        }

        boolean chunkingChanged = mergedStrategy != document.chunkStrategy()
                || mergedSize != document.chunkConfig().chunkSize()
                || mergedOverlap != document.chunkConfig().overlap();
        // revision = 0 的文档还没被处理过，没有"旧分块"可言——此时提示"需要重新分块"会让
        // 用户以为漏做了什么，其实他只要照常点「开始处理」。
        boolean needsRechunk = chunkingChanged && document.revision() > 0;
        return new DocumentUpdateResult(documents.findById(docId).orElseThrow(), needsRechunk);
    }

    /**
     * 启用 / 禁用文档（api.md §3）。
     *
     * <p>**禁用必须物理删除向量，而不是只改一个标记位**：检索是直接查向量表的，只改数据库
     * 标记的话文档内容照样会被检索到，"禁用"就完全没有效果。
     *
     * <p>**禁用时不动分块自己的 {@code enabled}**，启用时也只为 {@code enabled = true} 的分块
     * 重算向量——这两条是同一件事的两面：用户此前对个别分块的取舍要能原样恢复，而不是被
     * 一次文档级禁用抹平。
     */
    public SourceDocument setEnabled(long docId, boolean enabled) {
        SourceDocument document = requireDocument(docId);
        requireNotRunning(document);
        if (document.enabled() == enabled) {
            return document;
        }

        if (!enabled) {
            transactions.executeWithoutResult(status -> {
                documents.setEnabled(docId, false);
                vectors.deleteByDocId(docId);
            });
            return documents.findById(docId).orElseThrow();
        }

        List<DocumentChunk> alive = chunks.findEnabledByDocId(docId);
        if (alive.isEmpty()) {
            // 从未成功分块过（revision = 0），或分块全被删/全被禁用：没有向量可写，正常返回。
            documents.setEnabled(docId, true);
            return documents.findById(docId).orElseThrow();
        }

        // 事务外算完再开事务（本方法因此不能加 @Transactional，同 ChunkService）
        List<float[]> embeddings = embeddingClient.embed(alive.stream()
                .map(DocumentChunk::content).toList());
        List<Long> chunkIds = alive.stream().map(DocumentChunk::id).toList();
        transactions.executeWithoutResult(status -> {
            documents.setEnabled(docId, true);
            vectors.deleteByDocId(docId);
            vectors.insertAll(document.kbId(), docId, chunkIds, embeddings);
        });
        return documents.findById(docId).orElseThrow();
    }

    /**
     * 删除文档（data-model §4）。逻辑删除文档与其全部分块、物理删除向量、关闭定时同步。
     *
     * <p>**对象存储里的原始文件保留不删**（PRD §7.6 例外 2）：逻辑删除意味着可恢复，
     * 删了源文件就恢复不回来了。
     */
    @Transactional
    public void delete(long docId) {
        SourceDocument document = requireDocument(docId);
        requireNotRunning(document);
        vectors.deleteByDocId(docId);
        chunks.softDeleteByDocId(docId);
        documents.softDelete(docId);
    }

    private SourceDocument requireDocument(long docId) {
        return documents.findById(docId)
                .orElseThrow(() -> ApiException.notFound("文档不存在或已被删除。"));
    }

    /** `RUNNING` 是唯一的排他状态（ui-spec §3），message 与前端禁用按钮的 tooltip 逐字相同。 */
    private void requireNotRunning(SourceDocument document) {
        if (document.status() == DocumentStatus.RUNNING) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_PROCESSING",
                    "文档正在处理中，请等待处理完成后再操作。");
        }
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
