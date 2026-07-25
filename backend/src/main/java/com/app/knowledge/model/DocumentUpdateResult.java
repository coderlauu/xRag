package com.app.knowledge.model;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * 更新文档的响应 = 文档全字段 + {@code needsRechunk}（api.md §3）。
 *
 * <p>{@code needsRechunk} 存在的理由是**后端不替用户决定要不要花一次 Embedding 的钱**：
 * 改了分块参数不会自动重新分块（PRD §4.2），由前端拿这个标记去引导用户显式触发。
 * 偷偷重新分块对一份几百分块的文档意味着一次可观的模型调用费用，而用户可能只是想改个名字
 * 顺手调了下参数。
 */
public record DocumentUpdateResult(@JsonUnwrapped SourceDocument document, boolean needsRechunk) {}
