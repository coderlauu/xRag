package com.app.knowledge.search;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 按 {@code chunkId} 批量取来源的展示元信息的窄只读端口。
 *
 * <p>与 {@link ChunkTextLookup} 的差别不只是字段，而是<b>对逻辑删除的态度刚好相反</b>：
 * 取正文时分块已删就返回 {@code null}，调用方跳过该候选；取展示信息时<b>已删除的文档仍要返回</b>，
 * 因为 3B 的来源条目必须继续出现在响应里、只是界面显示"文档已删除"并禁用跳转
 * （[API 契约 §9](../../../../../../../../tech/qa-pipeline/api.md)、ui-spec §13.3）。
 * 把它们做成两个端口而不是一个带开关的方法，是为了让这条相反的语义无法被误用。
 *
 * <p>批量而非单条：一轮检索最多 topN 条来源，逐条查会把一次响应变成 N 次往返。
 *
 * <p><b>不返回正文</b>。正文只经 {@link ChunkTextLookup} 取，且绝不进入 3B 的 API 响应。
 */
@Component
public class ChunkDisplayLookup {

    /**
     * 一条来源的展示元信息。
     *
     * @param documentDeleted 文档或分块任一被删除/禁用都算 true——对用户而言"这条来源的原文
     *                        还能不能打开"是同一个问题，区分文档删除与分块禁用只会让界面多出
     *                        一种没人能解释的状态
     */
    public record ChunkDisplay(
            long chunkId,
            long knowledgeBaseId,
            String knowledgeBaseName,
            long documentId,
            String documentName,
            int chunkIndex,
            boolean documentDeleted) {}

    private final JdbcTemplate jdbc;

    public ChunkDisplayLookup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按传入顺序无关地返回 {@code chunkId -> 展示信息}。
     *
     * <p>查不到的 chunkId 不出现在结果里（物理删除的分块）。调用方决定怎么处理缺失，
     * 本端口不替它编造一条占位记录。
     */
    public Map<Long, ChunkDisplay> displaysOf(Collection<Long> chunkIds) {
        if (chunkIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", chunkIds.stream().map(id -> "?").toList());
        List<ChunkDisplay> rows = jdbc.query(
                """
                select c.id            as chunk_id,
                       c.chunk_index   as chunk_index,
                       kb.id           as kb_id,
                       kb.name         as kb_name,
                       d.id            as doc_id,
                       d.name          as doc_name,
                       (c.deleted or not c.enabled or d.deleted or not d.enabled or kb.deleted)
                                       as document_deleted
                  from document_chunk c
                  join source_document d on d.id = c.doc_id
                  join knowledge_base kb on kb.id = c.kb_id
                 where c.id in (%s)
                """
                        .formatted(placeholders),
                (rs, rowNum) -> new ChunkDisplay(
                        rs.getLong("chunk_id"),
                        rs.getLong("kb_id"),
                        rs.getString("kb_name"),
                        rs.getLong("doc_id"),
                        rs.getString("doc_name"),
                        rs.getInt("chunk_index"),
                        rs.getBoolean("document_deleted")),
                chunkIds.toArray());
        var byId = new LinkedHashMap<Long, ChunkDisplay>(rows.size());
        for (ChunkDisplay row : rows) {
            byId.put(row.chunkId(), row);
        }
        return byId;
    }

    /** 按 {@code kbId} 取知识库名，用于失败目标点名。已删除的库同样返回名字。 */
    public Map<Long, String> knowledgeBaseNames(Collection<Long> kbIds) {
        if (kbIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", kbIds.stream().map(id -> "?").toList());
        var names = new LinkedHashMap<Long, String>();
        jdbc.query(
                "select id, name from knowledge_base where id in (%s)".formatted(placeholders),
                rs -> {
                    names.put(rs.getLong("id"), rs.getString("name"));
                },
                kbIds.toArray());
        return names;
    }
}
