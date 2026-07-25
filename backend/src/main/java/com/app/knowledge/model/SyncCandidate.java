package com.app.knowledge.model;

/**
 * 定时同步扫描器需要的字段投影。
 *
 * <p>**不复用 {@link SourceDocument}**：变更检测要用 {@code contentHash} / {@code httpEtag} /
 * {@code httpLastModified} 三列，而它们**有意不在 {@code SourceDocument} 里**——那个 record 会被
 * 直接序列化成接口响应，而 api.md §3 的契约里没有这三个字段。为了内部的一个后台任务把它们
 * 加进去，等于让实现细节泄漏到对外契约上。
 */
public record SyncCandidate(
        long id,
        long kbId,
        String sourceUri,
        String fileKey,
        String contentHash,
        String httpEtag,
        String httpLastModified,
        String syncCron) {}
