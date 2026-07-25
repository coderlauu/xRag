package com.app.knowledge.service;

import com.app.knowledge.model.IngestionRun;
import com.app.knowledge.model.IngestionTriggerSource;
import com.app.knowledge.model.SourceDocument;
import com.app.knowledge.repository.IngestionRunRepository;
import com.app.knowledge.repository.SourceDocumentRepository;
import com.app.knowledge.web.ApiException;
import com.app.knowledge.web.PageResponse;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 触发入库与查询入库记录。执行本身在 {@code ingestion} 包里异步进行。 */
@Service
public class IngestionService {

    private final SourceDocumentRepository documents;
    private final IngestionRunRepository runs;

    public IngestionService(SourceDocumentRepository documents, IngestionRunRepository runs) {
        this.documents = documents;
        this.runs = runs;
    }

    /**
     * 触发分块。一个短事务，毫秒级返回，真正的处理由派发器异步接手。
     *
     * @return 新建的 {@code ingestion_run} id
     */
    @Transactional
    public long trigger(long docId, IngestionTriggerSource triggerSource) {
        SourceDocument document = documents.findById(docId)
                .orElseThrow(() -> ApiException.notFound("文档不存在或已被删除。"));

        // 给禁用文档分块会写入向量，与"禁用即不参与检索"直接矛盾。
        // message 必须是能直接展示的完整句子——INVALID_STATE 一个码对应多种情况，
        // 前端只能靠 message 区分（api.md §1）。
        if (!document.enabled()) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_STATE",
                    "文档已禁用，无法处理。请先启用文档。");
        }

        // CAS 抢占：检查与占位合并在一条 SQL 里，没有竞态窗口，因此不需要锁。
        if (!documents.claimForProcessing(docId)) {
            throw new ApiException(HttpStatus.CONFLICT, "DOCUMENT_PROCESSING",
                    "文档正在处理中，请等待处理完成后再操作。");
        }
        return runs.insertQueued(document.kbId(), docId, triggerSource);
    }

    @Transactional(readOnly = true)
    public PageResponse<IngestionRun> list(long docId, Integer page, Integer size) {
        if (documents.findById(docId).isEmpty()) {
            throw ApiException.notFound("文档不存在或已被删除。");
        }
        int normalizedPage = PageResponse.normalizePage(page);
        int normalizedSize = PageResponse.normalizeSize(size);
        List<IngestionRun> items = runs.findPageByDocId(docId, normalizedPage, normalizedSize);
        return new PageResponse<>(items, normalizedPage, normalizedSize, runs.countByDocId(docId));
    }

    @Transactional(readOnly = true)
    public Optional<IngestionRun> latestRun(long docId) {
        return runs.findLatestByDocId(docId);
    }
}
