package com.app.qa.generation;

public interface GenerationTasks {

    Submission submit(GenerationCommand command, GenerationObserver observer);

    StopResult stop(OwnedTask task);

    record Submission(GenerationTaskId taskId, boolean queued) {}

    record OwnedTask(GenerationTaskId taskId, java.util.UUID conversationId, java.util.UUID turnId) {
        public OwnedTask {
            if (taskId == null || conversationId == null || turnId == null) {
                throw new IllegalArgumentException("owned task fields must not be null");
            }
        }
    }

    record StopResult(boolean found, GenerationTaskStatus status, boolean alreadyTerminal) {
        static StopResult notFound() {
            return new StopResult(false, null, false);
        }

        static StopResult stopped(GenerationTaskStatus status, boolean alreadyTerminal) {
            return new StopResult(true, status, alreadyTerminal);
        }
    }
}
