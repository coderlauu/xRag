package com.app.qa.retrieval;

/**
 * 3B 阶段状态，与 {@code qa_turn_retrieval.ck_qa_retrieval_status} 一一对应。
 *
 * <p><b>{@code NO_RESULT} 与 {@code RETRIEVAL_FAILED} 是两个状态，不能合并</b>：
 * 一个是"查了但库里没有"（该改问法或补资料），一个是"没查成"（可重试）。
 * 合并会让用户做错下一步（PRD §12.3 / §12.4）。
 */
public enum RetrievalStatus {
    RETRIEVING,
    RETRIEVED,
    NO_RESULT,
    RETRIEVAL_FAILED
}
