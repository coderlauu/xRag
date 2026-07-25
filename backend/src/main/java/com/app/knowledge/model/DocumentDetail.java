package com.app.knowledge.model;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

/**
 * 文档详情 = 文档全字段 + 最近一次入库任务的摘要（api.md §3）。
 *
 * <p>把 {@code latestRun} 带在详情里，是为了让前端不必为了显示"上次处理结果"再发一个
 * 请求——轮询期间这个差别会被放大 N 倍。
 */
public record DocumentDetail(@JsonUnwrapped SourceDocument document, IngestionRun latestRun) {}
