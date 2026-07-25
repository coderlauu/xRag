package com.app.knowledge.service;

import com.app.knowledge.embedding.EmbeddingProperties;
import com.app.knowledge.model.KnowledgeBase;
import com.app.knowledge.repository.KnowledgeBaseRepository;
import com.app.knowledge.web.ApiException;
import com.app.knowledge.web.PageResponse;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 知识库的业务编排。事务边界只在本层，且事务内没有任何外部 IO。
 */
@Service
public class KnowledgeBaseService {

    private static final int NAME_MAX_LENGTH = 128;
    private static final int DESCRIPTION_MAX_LENGTH = 1024;

    private final KnowledgeBaseRepository repository;
    private final EmbeddingProperties embeddingProperties;

    public KnowledgeBaseService(KnowledgeBaseRepository repository, EmbeddingProperties embeddingProperties) {
        this.repository = repository;
        this.embeddingProperties = embeddingProperties;
    }

    /**
     * {@code embeddingModel} / {@code embeddingDimensions} 由服务端从全局配置写入，
     * 客户端传了忽略（api.md §2）。第一版全局单一 Embedding 配置，让每个知识库自己声明
     * 模型会立刻和 {@code vector(1024)} 这个列级固定的维度打架。
     */
    @Transactional
    public KnowledgeBase create(String name, String description) {
        String trimmedName = requireName(name);
        requireDescriptionLength(description);
        if (repository.existsByName(trimmedName)) {
            throw duplicateName(trimmedName);
        }
        long id;
        try {
            id = repository.insert(trimmedName, description,
                    embeddingProperties.getModel(), embeddingProperties.getDimensions());
        } catch (DuplicateKeyException raceLostToAnotherRequest) {
            // 上面的 existsByName 只是为了给出友好提示，真正的唯一性由部分唯一索引保证。
            // 两次请求几乎同时创建同名知识库时会走到这里，仍然要返回同一条用户文案。
            throw duplicateName(trimmedName);
        }
        return repository.findById(id).orElseThrow();
    }

    @Transactional(readOnly = true)
    public PageResponse<KnowledgeBase> list(Integer page, Integer size) {
        int normalizedPage = PageResponse.normalizePage(page);
        int normalizedSize = PageResponse.normalizeSize(size);
        List<KnowledgeBase> items = repository.findPage(normalizedPage, normalizedSize);
        return new PageResponse<>(items, normalizedPage, normalizedSize, repository.count());
    }

    @Transactional(readOnly = true)
    public KnowledgeBase get(long id) {
        return repository.findById(id).orElseThrow(KnowledgeBaseService::notFound);
    }

    /** 只接受 name / description。模型和维度不可改——改模型会让已有向量全部失效，那是重建不是编辑。 */
    @Transactional
    public KnowledgeBase update(long id, String name, String description) {
        String trimmedName = requireName(name);
        requireDescriptionLength(description);
        if (repository.existsByNameExcluding(trimmedName, id)) {
            throw duplicateName(trimmedName);
        }
        if (repository.update(id, trimmedName, description) == 0) {
            throw notFound();
        }
        return repository.findById(id).orElseThrow(KnowledgeBaseService::notFound);
    }

    /**
     * 级联逻辑删除（data-model.md §4）：向量物理删除，分块与文档逻辑删除，最后是知识库自身。
     * 四步在同一个事务内，中途失败整体回滚——否则会留下"知识库还在但分块已经没了"的中间态。
     *
     * <p>对象存储里的原始文件**不删**（PRD §7.6 例外 2）：逻辑删除意味着数据可恢复，
     * 删了源文件就恢复不回来了。
     *
     * <p>此时文档功能还没实现，前两步必然影响 0 行；仍然一次写对，因为等文档功能上线后
     * 再回来补，很容易漏掉其中一步。
     */
    @Transactional
    public void delete(long id) {
        if (repository.findById(id).isEmpty()) {
            throw notFound();
        }
        repository.deleteEmbeddingsByKbId(id);
        repository.softDeleteChunksByKbId(id);
        repository.softDeleteDocumentsByKbId(id);
        if (repository.softDelete(id) == 0) {
            throw notFound();
        }
    }

    private String requireName(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw ApiException.invalidRequest("知识库名称不能为空。");
        }
        if (trimmed.length() > NAME_MAX_LENGTH) {
            throw ApiException.invalidRequest(
                    "知识库名称最多 %d 个字符，当前 %d 个。".formatted(NAME_MAX_LENGTH, trimmed.length()));
        }
        return trimmed;
    }

    private void requireDescriptionLength(String description) {
        if (description != null && description.length() > DESCRIPTION_MAX_LENGTH) {
            throw ApiException.invalidRequest(
                    "知识库描述最多 %d 个字符，当前 %d 个。".formatted(DESCRIPTION_MAX_LENGTH, description.length()));
        }
    }

    private static ApiException duplicateName(String name) {
        return ApiException.invalidRequest("已存在同名知识库「%s」，请换一个名称。".formatted(name));
    }

    /**
     * "不存在"与"已逻辑删除"返回同一个结果（api.md §1）——否则调用方能从状态码差异推断出
     * 记录曾经存在，逻辑删除就变成半透明的了。
     */
    private static ApiException notFound() {
        return ApiException.notFound("知识库不存在或已被删除。");
    }
}
