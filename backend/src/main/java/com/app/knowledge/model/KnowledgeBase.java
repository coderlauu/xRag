package com.app.knowledge.model;

import java.time.OffsetDateTime;

/**
 * 知识库读模型，形状与 api.md §2 的响应体一一对应。
 *
 * <p>{@code documentCount} / {@code chunkCount} 不是表上的列，是查询时聚合出来的
 * （都带 {@code deleted = false}）。这里不再另建一层 web DTO——它会和本记录逐字段
 * 相同，多一层只是多一处要同步修改的地方。请求体是另一回事，它们有各自的校验规则，
 * 放在 {@code web} 包里。
 */
public record KnowledgeBase(
        long id,
        String name,
        String description,
        String embeddingModel,
        int embeddingDimensions,
        long documentCount,
        long chunkCount,
        OffsetDateTime createTime) {}
