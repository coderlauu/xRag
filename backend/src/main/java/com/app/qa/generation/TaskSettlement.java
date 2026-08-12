package com.app.qa.generation;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

final class TaskSettlement {

    private final GenerationTaskId taskId;
    private final TaskSettlementRepository repository;
    private final Runnable releaseResources;
    private final Consumer<Outcome> terminalObserver;
    private final AtomicBoolean sideEffectsCompleted = new AtomicBoolean();

    TaskSettlement(
            GenerationTaskId taskId,
            TaskSettlementRepository repository,
            Runnable releaseResources,
            Consumer<Outcome> terminalObserver) {
        if (taskId == null || repository == null || releaseResources == null || terminalObserver == null) {
            throw new IllegalArgumentException("settlement collaborators must not be null");
        }
        this.taskId = taskId;
        this.repository = repository;
        this.releaseResources = releaseResources;
        this.terminalObserver = terminalObserver;
    }

    Outcome trySettle(Request request) {
        var persisted = repository.trySettle(taskId, request);
        var outcome = new Outcome(
                persisted.won(), persisted.status(), persisted.terminalReason(),
                persisted.assistantMessageId(), persisted.saved());
        if (persisted.won() && sideEffectsCompleted.compareAndSet(false, true)) {
            try {
                terminalObserver.accept(outcome);
            } finally {
                releaseResources.run();
            }
        }
        return outcome;
    }

    record Request(GenerationTaskStatus status, String terminalReason, String answer) {
        Request {
            if (status == null || !status.isTerminal()) {
                throw new IllegalArgumentException("settlement status must be terminal");
            }
            if (terminalReason == null || terminalReason.isBlank() || terminalReason.length() > 64) {
                throw new IllegalArgumentException("terminal reason must contain 1 to 64 characters");
            }
            answer = answer == null ? "" : answer;
            if (status == GenerationTaskStatus.TIMED_OUT && !answer.isEmpty()) {
                throw new IllegalArgumentException("timed out queue tasks cannot contain an answer");
            }
        }

        static Request completed(String answer) {
            return new Request(GenerationTaskStatus.COMPLETED, "COMPLETED", answer);
        }

        static Request stopped(String answer) {
            return new Request(GenerationTaskStatus.STOPPED, "STOP_REQUESTED", answer);
        }

        static Request failed(GenerationFailureCode code, String answer) {
            if (code == null) {
                throw new IllegalArgumentException("failure code must not be null");
            }
            return new Request(GenerationTaskStatus.FAILED, code.name(), answer);
        }

        static Request timedOut() {
            return new Request(GenerationTaskStatus.TIMED_OUT, "QUEUE_WAIT_TIMEOUT", "");
        }

        boolean hasAnswer() {
            return !answer.isBlank();
        }

        boolean incomplete() {
            return status != GenerationTaskStatus.COMPLETED;
        }
    }

    record Outcome(
            boolean won,
            GenerationTaskStatus status,
            String terminalReason,
            UUID assistantMessageId,
            boolean saved) {}
}
