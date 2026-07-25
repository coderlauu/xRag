package com.app.knowledge.model;

import java.time.OffsetDateTime;

/**
 * 已落库的文档分块，字段与 api.md §4 的响应体一一对应。
 *
 * <p>与 {@link TextChunk} 的区别：那个是分块算法的输出（还没有 id），这个是库里的一行。
 *
 * <p>**不含 {@code kbId}**——表里有这个冗余列，但接口契约里没有。需要它的地方（写向量时）
 * 本来就要先加载父文档做状态校验，从文档上取即可，不必为此在响应里多暴露一个字段。
 *
 * @param tokenCount 启发式估算，**只用于界面展示**，不参与任何逻辑判断
 */
public record DocumentChunk(
        long id,
        long docId,
        int revision,
        int chunkIndex,
        String content,
        int charCount,
        int tokenCount,
        boolean enabled,
        OffsetDateTime createTime,
        OffsetDateTime updateTime) {}
