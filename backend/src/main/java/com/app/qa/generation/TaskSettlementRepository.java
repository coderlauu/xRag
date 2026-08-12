package com.app.qa.generation;

import java.util.UUID;

interface TaskSettlementRepository {

    PersistenceResult trySettle(GenerationTaskId taskId, TaskSettlement.Request request);

    record PersistenceResult(
            boolean won,
            GenerationTaskStatus status,
            String terminalReason,
            UUID assistantMessageId,
            boolean saved) {}
}
