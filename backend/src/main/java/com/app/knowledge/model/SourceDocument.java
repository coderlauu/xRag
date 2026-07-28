package com.app.knowledge.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.OffsetDateTime;

/**
 * 源文档读模型，形状对应 api.md §3 的响应体。
 *
 * @param chunkConfig 每文档独立的分块配置——定时同步重新分块时要沿用它，
 *                    否则用户特意调过的参数会在下一次自动同步时被改回默认值
 */
public record SourceDocument(
        long id,
        long kbId,
        String name,
        SourceType sourceType,
        String fileKey,
        @JsonIgnore String storageObjectId,
        Long fileSize,
        String contentType,
        String sourceUri,
        DocumentStatus status,
        int revision,
        int chunkCount,
        String errorMessage,
        boolean enabled,
        ChunkStrategy chunkStrategy,
        ChunkSize chunkConfig,
        boolean syncEnabled,
        String syncCron,
        OffsetDateTime nextSyncTime,
        OffsetDateTime lastSyncTime,
        OffsetDateTime createTime) {

    /** 响应体里 {@code chunkConfig} 是个嵌套对象（api.md §3），与 {@link ChunkConfig} 的差别是它不带策略和分隔符。 */
    public record ChunkSize(int chunkSize, int overlap) {}
}
