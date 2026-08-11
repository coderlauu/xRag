package com.app.qa.retrieval;

/**
 * 检索失败的归一化闭集。
 *
 * <p>与工具侧的 {@code ToolFailureCode} 同源同理由：拦住异常 message。原始异常可能带内部
 * 主机名、端口与连接串，而这些会一路进到界面上"哪个知识库没查成"的提示里（PRD §12.6）。
 * 想说得更细就往闭集里加码值，不要在这里开一个字符串口子。
 */
public enum RetrievalFailureCode {

    /** 检索超时。 */
    RETRIEVAL_TIMEOUT,
    /** 知识库暂不可用（连接失败、索引异常等）。 */
    KNOWLEDGE_BASE_UNAVAILABLE,
    /** 查询向量算不出来（Embedding 不可用）。 */
    QUERY_EMBEDDING_FAILED
}
