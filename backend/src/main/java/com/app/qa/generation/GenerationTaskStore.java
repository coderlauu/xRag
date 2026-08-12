package com.app.qa.generation;

import java.util.Optional;
import java.util.UUID;

interface GenerationTaskStore {

    void validateReady(GenerationCommand command);

    void create(GenerationTaskId taskId, GenerationCommand command, boolean queued);

    boolean markGenerating(GenerationTaskId taskId);

    Optional<StoredTask> findOwned(GenerationTasks.OwnedTask task);

    record StoredTask(GenerationTaskStatus status, UUID conversationId, UUID turnId) {}
}
