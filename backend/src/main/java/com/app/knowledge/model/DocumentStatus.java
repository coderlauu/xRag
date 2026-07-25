package com.app.knowledge.model;

/**
 * 源文档的处理状态，对应 {@code source_document.status} 的 check 约束取值。
 *
 * <pre>
 * PENDING ──触发分块──> RUNNING ──成功──> SUCCESS ──重新触发/定时同步──> RUNNING
 *                         │                                              │
 *                         └──失败/超时回收──> FAILED ──重新触发───────────┘
 * </pre>
 *
 * {@link #RUNNING} 是唯一的排他状态：抢占用的 CAS 条件是 {@code status <> 'RUNNING'}，
 * 意味着 PENDING/SUCCESS/FAILED 都可以被抢占触发。<strong>没有任何路径回到
 * {@link #PENDING}</strong>——它只表示"从未处理过"。
 */
public enum DocumentStatus {

    /** 已上传，从未成功分块过（此时 {@code revision = 0}）。 */
    PENDING,

    /** 有一次入库任务正在处理它。 */
    RUNNING,

    /** 最近一次入库任务成功。 */
    SUCCESS,

    /** 最近一次入库任务失败；用户可直接重新触发分块，不必重新上传。 */
    FAILED
}
