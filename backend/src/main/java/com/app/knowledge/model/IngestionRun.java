package com.app.knowledge.model;

import java.time.OffsetDateTime;

/**
 * 入库任务。既是执行记录，也是任务队列本身——不引入 MQ 的直接结果（ADR 0002）。
 *
 * @param phase 失败时说明失败在哪一步（CONTEXT.md 对「入库任务」的要求）；成功或排队中为 null
 */
public record IngestionRun(
        long id,
        long kbId,
        long docId,
        IngestionTriggerSource triggerSource,
        IngestionRunStatus status,
        IngestionPhase phase,
        Integer revision,
        Integer chunkCount,
        String errorMessage,
        OffsetDateTime startedTime,
        OffsetDateTime finishedTime,
        OffsetDateTime createTime) {}
