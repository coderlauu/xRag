package com.app.knowledge.search;

import java.util.List;
import java.util.Set;

/**
 * 3B 检索的窄只读端口（架构 §19.1）。
 *
 * <p><b>由知识库模块提供，问答模块不拼 chunk 表 SQL。</b>两个理由：
 * 一是跨模块直接写别人的表结构会把两个模块的迁移绑死；二是关键词查询必须与索引期用
 * <b>同一个切词器实例</b>，把切词留在这一侧，这条一致性就是结构性的，不靠调用方自觉。
 *
 * <p>因此两个方法的入参形态刻意不同：向量查询收**已经算好的向量**（Embedding 是
 * {@code com.app.ai} 的能力，问答侧调），关键词查询收**原始查询串**（切词在这一侧）。
 */
public interface ChunkSearchPort {

    /**
     * 一条召回。<b>不含正文</b>——正文按 {@code chunkId} 另取，这样它天然跟随知识库的逻辑删除。
     *
     * <p>{@code score} 只在通道内部用于排序，绝不出现在 API 或界面上（PRD §12.2）；
     * 融合用的是排名不是分数（ADR 0008 决策 4）。
     */
    record ChunkMatch(long chunkId, long kbId, long docId, double score) {}

    /**
     * 向量检索。{@code kbIds} 为空集表示**全局检索**（不带 kb 过滤）——这正是 pgvector 单表
     * 形态带来的简化：全局就是一条不带 where 的 SQL，结果还比"N 路各取 TopK 再合并"更正确。
     */
    List<ChunkMatch> searchByVector(Set<Long> kbIds, float[] queryVector, int limit);

    /**
     * 关键词检索。{@code rawQuery} 是未切词的查询串，本方法内部用与索引期相同的切词器处理。
     *
     * <p>切不出任何 token 时返回空列表，而不是抛错——纯标点查询在关键词通道上没有结果，
     * 这是正常情况，不是故障。
     */
    List<ChunkMatch> searchByKeyword(Set<Long> kbIds, String rawQuery, int limit);
}
