package com.app.qa.web;

import java.util.List;
import java.util.UUID;

/**
 * 3B 给 turn 响应新增的两个块（[API 契约 §9](../../../../../../../tech/qa-pipeline/api.md)）。
 *
 * <p>这些 record 就是契约的那份 JSON，字段名逐字对应。它们**刻意不复用领域类型**：
 * {@code FusedCandidate} 带分数、{@code ToolCallRow} 带参数原文，直接序列化会把不该出去的
 * 东西一起带出去。中间隔一层视图，是让"什么能出去"变成一处可扫描的清单，而不是散在
 * 各个领域类型的字段上。
 */
public final class TurnResultView {

    private TurnResultView() {}

    /**
     * 检索块。turn 未进过 3B 时整块为 {@code null}，不是空对象——"没检索过"与"检索了没结果"
     * 是两种状态，用同一个形状表达会让客户端分不开。
     *
     * @param partial 服务端算好的显式字段，客户端不得用 {@code sources.length} 推断（PRD §12.4）
     */
    public record Retrieval(
            String status,
            boolean partial,
            boolean rerankApplied,
            String degradation,
            List<Source> sources,
            List<FailedTarget> failedTargets) {}

    /**
     * 一条来源。
     *
     * <p><b>没有分数、没有正文、没有通道名。</b>顺序由 {@code ordinal} 表达；正文另经知识库
     * 接口按 {@code chunkId} 取，于是天然跟随逻辑删除；通道名只进处理详情，避免"命中两次
     * 更可信"的错误暗示（契约 §9）。
     *
     * @param documentDeleted 为 true 时条目仍返回，界面显示"文档已删除"并禁用跳转
     */
    public record Source(
            int ordinal,
            long chunkId,
            Long knowledgeBaseId,
            String knowledgeBaseName,
            Long documentId,
            String documentName,
            Integer chunkIndex,
            boolean documentDeleted) {}

    /** 一个没查成的知识库。{@code failureCode} 是归一化闭集，永不透传异常 message。 */
    public record FailedTarget(Long knowledgeBaseId, String knowledgeBaseName, String failureCode) {}

    /**
     * 一次工具调用。
     *
     * <p>{@code displayName} 与 {@code description} 来自服务端白名单配置，不是模型生成的
     * （架构 §21.2）——否则确认卡就是在让模型描述自己要做什么。
     */
    public record Tool(
            UUID toolCallId,
            int ordinal,
            String toolName,
            String displayName,
            String description,
            boolean sideEffect,
            String status,
            List<Parameter> parameters,
            String resultText,
            String failureCode,
            Integer latencyMs) {}

    /**
     * 一个复核通过后的参数。模型编造的字段不出现在这里（契约 §9）。
     *
     * <p>{@code name} 是 Schema 的 {@code title}，不是属性名——ui-spec §13.7.2 要展示的是
     * "Schema 声明的名称"。**Schema 原文不出现在响应里**，这里只有名字、值与必填标记。
     */
    public record Parameter(String name, String value, boolean required) {}
}
