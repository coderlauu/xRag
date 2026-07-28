package com.app.knowledge.model;

/**
 * 一次入库任务绑定的源文档版本快照。
 *
 * <p>这些字段只供后台执行与成功切换版本使用，不属于入库历史的对外响应契约。
 */
public record IngestionInput(
        int revision,
        String fileKey,
        String contentHash,
        Long fileSize,
        String contentType,
        String httpEtag,
        String httpLastModified) {

    public static IngestionInput current(SourceDocument document) {
        return new IngestionInput(document.revision(), document.fileKey(), null,
                null, null, null, null);
    }
}
