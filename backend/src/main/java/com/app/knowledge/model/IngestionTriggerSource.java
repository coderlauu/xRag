package com.app.knowledge.model;

/** 入库任务由什么触发，对应 {@code ingestion_run.trigger_source} 的 check 约束取值。 */
public enum IngestionTriggerSource {

    /** 用户在界面上手动触发分块。 */
    MANUAL,

    /** 定时同步扫描发现远程内容变化后触发。 */
    SCHEDULED
}
