package com.app.knowledge.ingestion;

import com.app.knowledge.embedding.EmbeddingClient;
import com.app.knowledge.model.ChunkConfig;
import com.app.knowledge.model.IngestionPhase;
import com.app.knowledge.model.IngestionRun;
import com.app.knowledge.model.SourceDocument;
import com.app.knowledge.model.TextChunk;
import com.app.knowledge.repository.DocumentChunkRepository;
import com.app.knowledge.repository.IngestionRunRepository;
import com.app.knowledge.repository.SourceDocumentRepository;
import com.app.knowledge.vector.ChunkVectorRepository;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;

/**
 * 入库任务的执行体：architecture.md §3.2 的六步流程。
 *
 * <p>**第 4 步和第 5 步的顺序是整个流程的核心**：先在事务外把所有向量算完，再开事务写库。
 * Embedding 是按分块数线性增长的网络调用（一份百页 PDF 可能几百个分块），放进事务会让
 * 数据库连接被占用几十秒。第 5 步事务内全是本地写入，耗时可控。
 */
@Component
public class IngestionExecutor {

    private static final Logger LOGGER = LoggerFactory.getLogger(IngestionExecutor.class);

    private final IngestionRunRepository runs;
    private final SourceDocumentRepository documents;
    private final DocumentChunkRepository chunks;
    private final ChunkVectorRepository vectors;
    private final TextExtractor extractor;
    private final TextChunker chunker;
    private final EmbeddingClient embeddingClient;
    private final S3Client s3;
    private final String bucket;
    private final TransactionOperations transactions;
    private final TransactionOperations newTransactions;

    public IngestionExecutor(IngestionRunRepository runs, SourceDocumentRepository documents,
            DocumentChunkRepository chunks, ChunkVectorRepository vectors, TextExtractor extractor,
            TextChunker chunker, EmbeddingClient embeddingClient, S3Client s3,
            @Value("${app.storage.bucket}") String bucket, PlatformTransactionManager transactionManager) {
        this.runs = runs;
        this.documents = documents;
        this.chunks = chunks;
        this.vectors = vectors;
        this.extractor = extractor;
        this.chunker = chunker;
        this.embeddingClient = embeddingClient;
        this.s3 = s3;
        this.bucket = bucket;
        this.transactions = new TransactionTemplate(transactionManager);
        TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.newTransactions = requiresNew;
    }

    /**
     * 由派发器在线程池里调用。本方法自己**不能**把失败悄悄带走——失败要落到记录里。
     *
     * <p>**捕获 {@code Throwable} 而不是 {@code Exception}**：只接 Exception 的话，
     * {@code OutOfMemoryError}、{@code NoClassDefFoundError} 这类 {@code Error} 会直接穿过去，
     * 于是 {@link #markFailed} 从不执行、任务永远停在 `RUNNING`，只能等心跳超时兜底——
     * 而兜底消息说的是"超时卡死"，**真实原因就此永久丢失**，界面上再也查不到。
     *
     * <p>记录之后 Error 仍然重新抛出：它意味着 JVM 已处在不可信状态，吞掉它只会把问题
     * 推迟到更难定位的地方。这里要的只是"先把原因写进记录"，不是"当作普通失败处理"。
     */
    public void execute(long runId) {
        IngestionRun run = runs.findById(runId).orElse(null);
        if (run == null) {
            LOGGER.warn("入库任务 {} 不存在，跳过", runId);
            return;
        }
        try {
            executeSteps(run);
        } catch (Throwable failure) {
            LOGGER.warn("入库任务 {} 失败：{}", runId, failure.toString());
            markFailed(run, failure);
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    private void executeSteps(IngestionRun run) throws Exception {
        SourceDocument document = documents.findById(run.docId())
                .orElseThrow(() -> new IllegalStateException("文档不存在或已被删除"));

        File temp = null;
        List<TextChunk> textChunks;
        List<float[]> embeddings;
        try {
            // 步骤 1：下载到临时文件（事务外）
            runs.updatePhase(run.id(), IngestionPhase.DOWNLOAD);
            temp = Files.createTempFile("xrag-ingest-", ".bin").toFile();
            try (InputStream source = s3.getObject(
                    GetObjectRequest.builder().bucket(bucket).key(run.input().fileKey()).build())) {
                Files.copy(source, temp.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            // 步骤 2：提取纯文本（事务外）
            runs.updatePhase(run.id(), IngestionPhase.EXTRACT);
            String text;
            try (InputStream stream = Files.newInputStream(temp.toPath())) {
                text = extractor.extract(stream, document.name());
            }

            // 步骤 3：切分（事务外）
            runs.updatePhase(run.id(), IngestionPhase.CHUNK);
            textChunks = chunker.chunk(text, new ChunkConfig(document.chunkStrategy(),
                    document.chunkConfig().chunkSize(), document.chunkConfig().overlap(),
                    ChunkConfig.DEFAULT_SEPARATORS));

            // 步骤 4：批量算向量（事务外，关键）
            runs.updatePhase(run.id(), IngestionPhase.EMBED);
            embeddings = embeddingClient.embed(textChunks.stream().map(TextChunk::content).toList());
        } finally {
            deleteQuietly(temp);
        }

        // 步骤 5：一个短事务内完成删旧插新
        runs.updatePhase(run.id(), IngestionPhase.PERSIST);
        int revision = run.input().revision() + 1;
        persist(document, run, revision, textChunks, embeddings);

        // 步骤 6：标记任务成功
        runs.markSuccess(run.id(), revision, textChunks.size());
        LOGGER.info("入库任务 {} 完成：文档 {} revision {} 共 {} 个分块",
                run.id(), document.id(), revision, textChunks.size());
    }

    /**
     * 第 5 步：删旧插新 + 更新文档，全在一个事务里。
     *
     * <p>"删旧插新"是重新分块的通用语义——**首次分块时旧分块集合为空，因此首次和重新分块
     * 走完全同一段代码**，不需要区分。向量物理删除、分块逻辑删除，两者的差别见 PRD §7.6。
     *
     * <p>**用编程式事务而不是 `@Transactional`**：本方法由同类的 {@code executeSteps} 直接
     * 调用，自调用走不到 Spring 的代理，注解会**静默失效**——五步写入会各自独立提交，
     * 中途失败就留下"旧分块删了、新分块只插了一半"的残局，而且没有任何报错提示事务没生效。
     */
    void persist(SourceDocument document, IngestionRun run, int revision, List<TextChunk> textChunks,
            List<float[]> embeddings) {
        transactions.executeWithoutResult(status -> {
            vectors.deleteByDocId(document.id());
            chunks.softDeleteOlderRevisions(document.id(), revision);
            List<Long> chunkIds = chunks.insertAll(document.kbId(), document.id(), revision, textChunks);
            vectors.insertAll(document.kbId(), document.id(), chunkIds, embeddings);
            documents.markSuccess(document.id(), revision, textChunks.size(), run.input());
        });
    }

    /**
     * 失败标记必须在**独立事务**里：沿用外层事务的话，失败信息会被外层回滚一起冲掉，
     * 结果是文档卡在 RUNNING、用户既看不到原因也重试不了。
     */
    void markFailed(IngestionRun run, Throwable failure) {
        String message = failure.getMessage() == null ? failure.toString() : failure.getMessage();
        newTransactions.executeWithoutResult(status -> {
            runs.markFailed(run.id(), message);
            documents.markFailed(run.docId(), message);
        });
    }

    void heartbeat(long runId) {
        runs.heartbeat(runId);
    }

    private void deleteQuietly(File temp) {
        if (temp == null) {
            return;
        }
        try {
            Files.deleteIfExists(temp.toPath());
        } catch (Exception exception) {
            LOGGER.warn("临时文件删除失败，需要人工清理：{}", temp.getAbsolutePath());
        }
    }
}
