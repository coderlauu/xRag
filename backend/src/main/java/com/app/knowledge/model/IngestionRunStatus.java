package com.app.knowledge.model;

/**
 * 入库任务的状态，对应 {@code ingestion_run.status} 的 check 约束取值。
 *
 * 两条终态路径：{@code QUEUED → RUNNING → SUCCESS|FAILED}，以及定时同步判断出内容
 * 未变时的 {@code QUEUED → SKIPPED}。
 */
public enum IngestionRunStatus {

    /** 待办。轮询扫的就是这批行，它们构成了这个模块的异步任务队列。 */
    QUEUED,

    /** 已被某个执行线程抢占，正在处理。 */
    RUNNING,

    SUCCESS,

    FAILED,

    /**
     * 定时同步检查过，但内容没有变化，因此没有真正执行分块。
     *
     * 这个状态必须落库：否则用户在界面上看到"定时同步开着却从来没有执行记录"，
     * 无法区分"检查过没变化"和"调度根本没跑"。
     */
    SKIPPED
}
