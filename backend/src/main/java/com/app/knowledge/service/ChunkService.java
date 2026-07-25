package com.app.knowledge.service;

import com.app.knowledge.embedding.EmbeddingClient;
import com.app.knowledge.ingestion.ContentHash;
import com.app.knowledge.ingestion.TokenEstimator;
import com.app.knowledge.model.DocumentChunk;
import com.app.knowledge.model.DocumentStatus;
import com.app.knowledge.model.SourceDocument;
import com.app.knowledge.model.TextChunk;
import com.app.knowledge.repository.DocumentChunkRepository;
import com.app.knowledge.repository.SourceDocumentRepository;
import com.app.knowledge.vector.ChunkVectorRepository;
import com.app.knowledge.web.ApiException;
import com.app.knowledge.web.PageResponse;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 分块的查看、编辑与删除（api.md §4）。
 *
 * <p>这是 PRD §4.4「手动干预」的落点：自动分块无法覆盖所有情况，手工改分块是永久兜底能力，
 * 不是临时方案。
 *
 * <p>**本类的写方法一律不加 {@code @Transactional}，改用编程式事务**。理由与
 * {@code IngestionExecutor} 相同：Embedding 是网络调用，必须在事务外算完再开事务写库；
 * 用注解的话事务范围是整个方法，等于把一次外部调用圈进事务里。编程式事务还让"事务从哪
 * 开始到哪结束"在调用点直接可见，这正是本模块最在意的纪律。
 */
@Service
public class ChunkService {

    private final DocumentChunkRepository chunks;
    private final SourceDocumentRepository documents;
    private final ChunkVectorRepository vectors;
    private final EmbeddingClient embeddingClient;
    private final TransactionOperations transactions;

    public ChunkService(DocumentChunkRepository chunks, SourceDocumentRepository documents,
            ChunkVectorRepository vectors, EmbeddingClient embeddingClient,
            PlatformTransactionManager transactionManager) {
        this.chunks = chunks;
        this.documents = documents;
        this.vectors = vectors;
        this.embeddingClient = embeddingClient;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public PageResponse<DocumentChunk> list(long docId, Boolean enabled, Integer page, Integer size) {
        requireDocument(docId);
        int normalizedPage = PageResponse.normalizePage(page);
        int normalizedSize = PageResponse.normalizeSize(size);
        List<DocumentChunk> items = chunks.findPage(docId, enabled, normalizedPage, normalizedSize);
        return new PageResponse<>(items, normalizedPage, normalizedSize, chunks.count(docId, enabled));
    }

    /**
     * 修改分块内容。
     *
     * <p>**内容与库中完全一致时直接返回，不做任何向量操作**（api.md §4）。两个理由：省下
     * 一次按 token 计费的 Embedding 调用；避免"删旧插新"期间该分块短暂不在向量库里的检索
     * 空窗——那个窗口里的检索会静默地少召回一条，没有任何报错。
     */
    public DocumentChunk updateContent(long chunkId, String content) {
        if (content == null || content.isBlank()) {
            throw ApiException.invalidRequest("分块内容不能为空。");
        }
        DocumentChunk chunk = requireChunk(chunkId);
        SourceDocument document = requireDocument(chunk.docId());
        requireNotRunning(document);

        if (content.equals(chunk.content())) {
            return chunk;
        }

        // 禁用的分块本来就没有向量（向量表不变量 2），改内容也不该让它凭空出现一条。
        // 顺带省下这次 Embedding 调用——用户禁用它就是不想让它参与检索。
        float[] vector = chunk.enabled() ? embedOne(content) : null;

        int charCount = content.length();
        int tokenCount = TokenEstimator.estimate(content);
        String contentHash = ContentHash.sha256(content);

        transactions.executeWithoutResult(status -> {
            chunks.updateContent(chunkId, content, charCount, tokenCount, contentHash);
            if (vector != null) {
                // 向量"更新"底层就是删旧插新：存的是 (chunk_id, embedding)，内容变了向量
                // 完全不同，没有"改部分维度"这种操作。
                vectors.deleteByChunkId(chunkId);
                vectors.insertAll(document.kbId(), document.id(), List.of(chunkId), List.of(vector));
            }
        });
        return chunks.findById(chunkId).orElseThrow();
    }

    /**
     * 手动新增分块（PRD §4.4）。三重前置校验缺一不可，见 {@link #requireInsertable}。
     *
     * @param chunkIndex 缺省时取 {@code max(chunk_index) + 1}；指定时**不重排**其他分块的序号——
     *                   序号只是排序展示字段、允许重复，为插入一条而重排整份文档是一次大范围
     *                   写入，代价与收益不成比例（data-model §3.3）
     */
    public DocumentChunk create(long docId, String content, Integer chunkIndex) {
        if (content == null || content.isBlank()) {
            throw ApiException.invalidRequest("分块内容不能为空。");
        }
        SourceDocument document = requireDocument(docId);
        requireNotRunning(document);
        requireInsertable(document);

        int index = chunkIndex != null ? chunkIndex : chunks.maxChunkIndex(docId) + 1;
        if (index < 0) {
            throw ApiException.invalidRequest("分块序号不能为负数。");
        }

        float[] vector = embedOne(content);
        TextChunk pending = new TextChunk(index, content, content.length(),
                TokenEstimator.estimate(content), ContentHash.sha256(content));

        Long chunkId = transactions.execute(status -> {
            long id = chunks.insertAll(document.kbId(), docId, document.revision(),
                    List.of(pending)).get(0);
            vectors.insertAll(document.kbId(), docId, List.of(id), List.of(vector));
            documents.incrementChunkCount(docId);
            return id;
        });
        return chunks.findById(chunkId).orElseThrow();
    }

    /**
     * 单条启用/禁用（api.md §4）。
     *
     * <p>**启用和禁用的校验是不对称的，这不是疏忽**：启用要校验父文档也处于启用，否则会出现
     * "文档整体不参与检索、但它里面这一段却能被检索到"的矛盾；禁用不需要父文档处于任何特定
     * 状态——把东西从检索里拿掉，任何时候都是安全的。
     */
    public DocumentChunk setEnabled(long chunkId, boolean enabled) {
        DocumentChunk chunk = requireChunk(chunkId);
        SourceDocument document = requireDocument(chunk.docId());
        requireNotRunning(document);

        // 已是目标状态就直接返回：批量场景里"部分已达目标"是常态，为它做一次删向量
        // 再重算写回，既花钱又制造一段该分块不在向量库里的检索空窗。
        if (chunk.enabled() == enabled) {
            return chunk;
        }

        if (enabled) {
            if (!document.enabled()) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                        "文档已禁用，无法单独启用其中的分块。请先启用文档。");
            }
            float[] vector = embedOne(chunk.content());
            transactions.executeWithoutResult(status -> {
                chunks.setEnabled(chunkId, true);
                // 先删再插：正常情况下禁用的分块没有向量，这条 delete 影响 0 行。留着是因为
                // chunk_id 是主键，万一有残留行，insert 会直接冲突失败。
                vectors.deleteByChunkId(chunkId);
                vectors.insertAll(document.kbId(), document.id(), List.of(chunkId), List.of(vector));
            });
        } else {
            transactions.executeWithoutResult(status -> {
                chunks.setEnabled(chunkId, false);
                vectors.deleteByChunkId(chunkId);
            });
        }
        return chunks.findById(chunkId).orElseThrow();
    }

    /**
     * 批量启用/禁用的结果（api.md §4）。三个数字始终满足
     * {@code requested == changed + alreadyInTargetState}。
     */
    public record BatchToggleResult(int requested, int changed, int alreadyInTargetState) {}

    /** 单次批量上限。超过后批量 Embedding + 向量写入的总耗时会让接口响应时间失控。 */
    public static final int MAX_BATCH_SIZE = 500;

    /**
     * 批量启用/禁用（api.md §4）。
     *
     * <p>**本方法绝对不能加 {@code @Transactional}**：加了之后下面的 {@code transactions}
     * 会加入外层事务，事务边界变成整个方法，那次按分块数线性增长的 Embedding 调用就被圈进
     * 了事务里——500 个分块的批量启用会让一个数据库连接被占用几十秒。用编程式事务把范围
     * 精确框在数据库写入那几行，是这个接口全部设计含量所在（architecture.md §3.5）。
     *
     * <p>校验策略是**整批失败**而不是跳过无效项：放宽成"跳过无效的继续处理"看起来友好，
     * 但调用方会以为 500 个都处理了、实际只处理了 498 个，这种模糊比直接报错难排查得多。
     */
    public BatchToggleResult setEnabledBatch(long docId, List<Long> chunkIds, boolean enabled) {
        if (chunkIds == null || chunkIds.isEmpty()) {
            // 不支持"全部启用/禁用"语义：那个需求该用文档级的 PATCH /documents/{docId}/enabled，
            // 语义更准确，也不会因为分页而只覆盖到当前这一页。
            throw ApiException.invalidRequest("请至少选择一个分块。");
        }
        if (chunkIds.size() > MAX_BATCH_SIZE) {
            throw ApiException.invalidRequest(
                    "单次最多操作 %d 个分块，请分批处理。".formatted(MAX_BATCH_SIZE));
        }
        SourceDocument document = requireDocument(docId);
        requireNotRunning(document);

        // 用去重后的集合做校验与计数，三个返回数字才恒等（requested == changed + already）。
        // 同一个 id 传两次不是错误，但它只对应一个分块。
        Set<Long> distinctIds = new LinkedHashSet<>(chunkIds);
        List<DocumentChunk> found = chunks.findAllByDocIdAndIds(docId, distinctIds);
        if (found.size() != distinctIds.size()) {
            throw ApiException.invalidRequest("选中的分块里有不存在、已删除或不属于该文档的，请刷新后重试。");
        }

        List<DocumentChunk> pending = found.stream().filter(chunk -> chunk.enabled() != enabled).toList();
        int already = found.size() - pending.size();
        // "部分已达目标"是批量操作的常态，全部已达目标也一样。为它报错会让前端不得不先查
        // 一遍状态再调用。
        if (pending.isEmpty()) {
            return new BatchToggleResult(distinctIds.size(), 0, already);
        }

        List<Long> ids = pending.stream().map(DocumentChunk::id).toList();
        if (enabled) {
            if (!document.enabled()) {
                throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                        "文档已禁用，无法单独启用其中的分块。请先启用文档。");
            }
            // 事务外：整批算完向量再进事务。
            List<float[]> embeddings = embeddingClient.embed(
                    pending.stream().map(DocumentChunk::content).toList());
            transactions.executeWithoutResult(status -> {
                chunks.setEnabledAll(ids, true);
                vectors.deleteByChunkIds(ids);
                vectors.insertAll(document.kbId(), docId, ids, embeddings);
            });
        } else {
            transactions.executeWithoutResult(status -> {
                chunks.setEnabledAll(ids, false);
                vectors.deleteByChunkIds(ids);
            });
        }
        // 两条路径都只碰本次真正变更的分块，不做全量重建——必须与单条接口一致：全量重建
        // 若对批量是必要的，单条也该做；若不必要，批量也没必要。
        return new BatchToggleResult(distinctIds.size(), pending.size(), already);
    }

    /** 逻辑删除分块 + 物理删除其向量 + 文档冗余计数递减，三件事必须在同一个事务里。 */
    public void delete(long chunkId) {
        DocumentChunk chunk = requireChunk(chunkId);
        SourceDocument document = requireDocument(chunk.docId());
        requireNotRunning(document);

        transactions.executeWithoutResult(status -> {
            vectors.deleteByChunkId(chunkId);
            chunks.softDelete(chunkId);
            documents.decrementChunkCount(document.id());
        });
    }

    private float[] embedOne(String content) {
        List<float[]> result = embeddingClient.embed(List.of(content));
        if (result.size() != 1) {
            throw new IllegalStateException("Embedding 返回了 %d 条向量，期望 1 条".formatted(result.size()));
        }
        return result.get(0);
    }

    private DocumentChunk requireChunk(long chunkId) {
        return chunks.findById(chunkId)
                .orElseThrow(() -> ApiException.notFound("分块不存在或已被删除。"));
    }

    private SourceDocument requireDocument(long docId) {
        return documents.findById(docId)
                .orElseThrow(() -> ApiException.notFound("文档不存在或已被删除。"));
    }

    /**
     * 新增分块的第三重校验，**最容易漏的一条**：新增的分块会立刻算向量写进检索库，父文档
     * 处于禁用时就出现"文档整体不参与检索、但它里面新加的这段却能被检索到"的矛盾。
     */
    private void requireInsertable(SourceDocument document) {
        if (!document.enabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                    "文档已禁用，无法新增分块。请先启用文档。");
        }
    }

    /**
     * {@code RUNNING} 是唯一的排他状态（ui-spec §3）：重新处理会逻辑删除全部旧分块再写入
     * 新的，此刻编辑或删除某个旧分块，改动马上就会被覆盖掉，用户却看不出来。
     *
     * <p>{@code message} 与前端禁用按钮的 tooltip **逐字相同**——界面上按钮已经灰了，但
     * 状态是上一次轮询的快照，竞态下用户仍可能撞上这个 409，两处说法一致才不会让人以为
     * 遇到了两种不同的问题。
     */
    private void requireNotRunning(SourceDocument document) {
        if (document.status() == DocumentStatus.RUNNING) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_PROCESSING",
                    "文档正在处理中，请等待处理完成后再操作。");
        }
    }
}
