package com.app.knowledge.model;

/**
 * 入库任务的执行阶段，记录在 {@code ingestion_run.phase} 上，用于说明失败发生在哪一步
 * ——这是 CONTEXT.md 对「入库任务」的明确要求。
 *
 * 枚举顺序即执行顺序。{@link #EMBED} 与 {@link #PERSIST} 的分界同时是事务边界：
 * EMBED 及之前的阶段全在事务外（都是耗时外部 IO），只有 PERSIST 在一个短事务里。
 */
public enum IngestionPhase {

    /** 从对象存储（或远程 URL）取到原始文件。 */
    DOWNLOAD,

    /** 用 Apache Tika 提取纯文本。 */
    EXTRACT,

    /** 按分块策略切分。 */
    CHUNK,

    /** 批量调用 Embedding API 算出全部向量。 */
    EMBED,

    /** 在一个短事务里落库：删旧分块与其向量、插新分块与新向量、更新文档。 */
    PERSIST
}
