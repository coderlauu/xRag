package com.app.knowledge.model;

/** 源文档的来源类型，对应 {@code source_document.source_type} 的 check 约束取值。 */
public enum SourceType {

    /** 用户上传的文件。 */
    FILE,

    /** 从 URL 抓取的网页或远程文件；只有这种来源能开启定时同步。 */
    URL
}
