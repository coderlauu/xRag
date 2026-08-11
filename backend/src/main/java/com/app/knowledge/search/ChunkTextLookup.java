package com.app.knowledge.search;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 按 {@code chunkId} 取分块正文的窄只读端口。
 *
 * <p>与 {@link ChunkSearchPort} 分开，是因为两者的调用时机与代价完全不同：检索每轮一次，
 * 取正文只在精排前对候选做，而且**正文绝不进入落库与 API**（数据模型 §14.2）——
 * 来源表只存 {@code chunkId}，正文按需现取，于是它天然跟随知识库的逻辑删除。
 */
@Component
public class ChunkTextLookup {

    private final JdbcTemplate jdbc;

    public ChunkTextLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 取不到（分块已删或已禁用）返回 {@code null}，调用方据此跳过该候选。 */
    public String text(long chunkId) {
        return jdbc
                .query(
                        "select content from document_chunk where id = ? and deleted = false and enabled = true",
                        (rs, rowNum) -> rs.getString("content"),
                        chunkId)
                .stream()
                .findFirst()
                .orElse(null);
    }
}
