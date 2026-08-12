package com.app.qa.generation;

import java.util.Objects;
import java.util.UUID;

public sealed interface GenerationEvent
        permits GenerationEvent.Accepted,
                GenerationEvent.Queued,
                GenerationEvent.GenerationStarted,
                GenerationEvent.AnswerDelta,
                GenerationEvent.Completed,
                GenerationEvent.Stopped,
                GenerationEvent.Failed,
                GenerationEvent.TimedOut {

    long sequence();

    record Accepted(
            long sequence,
            GenerationTaskId taskId,
            UUID conversationId,
            UUID turnId,
            UUID userMessageId) implements GenerationEvent {

        public Accepted {
            requireSequence(sequence);
            Objects.requireNonNull(taskId, "taskId");
            Objects.requireNonNull(conversationId, "conversationId");
            Objects.requireNonNull(turnId, "turnId");
            Objects.requireNonNull(userMessageId, "userMessageId");
        }
    }

    record Queued(long sequence) implements GenerationEvent {

        public Queued {
            requireSequence(sequence);
        }
    }

    record GenerationStarted(long sequence, boolean queuedOnce) implements GenerationEvent {

        public GenerationStarted {
            requireSequence(sequence);
        }
    }

    record AnswerDelta(long sequence, String text) implements GenerationEvent {

        public AnswerDelta {
            requireSequence(sequence);
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("text must not be empty");
            }
        }
    }

    record Completed(long sequence, UUID assistantMessageId, boolean saved) implements GenerationEvent {

        public Completed {
            requireSequence(sequence);
            requireSavedMessageId(assistantMessageId, saved);
        }
    }

    record Stopped(long sequence, UUID assistantMessageId, boolean saved, boolean hasPartialAnswer)
            implements GenerationEvent {

        public Stopped {
            requireSequence(sequence);
            requireSavedMessageId(assistantMessageId, saved);
        }
    }

    record Failed(
            long sequence,
            GenerationFailureCode code,
            boolean retryable,
            UUID assistantMessageId,
            boolean saved) implements GenerationEvent {

        public Failed {
            requireSequence(sequence);
            Objects.requireNonNull(code, "code must not be null");
            requireSavedMessageId(assistantMessageId, saved);
        }
    }

    record TimedOut(long sequence) implements GenerationEvent {

        public TimedOut {
            requireSequence(sequence);
        }
    }

    private static void requireSequence(long sequence) {
        if (sequence < 1) {
            throw new IllegalArgumentException("sequence must be positive");
        }
    }

    private static void requireSavedMessageId(UUID assistantMessageId, boolean saved) {
        if (saved && assistantMessageId == null) {
            throw new IllegalArgumentException("saved events require an assistant message id");
        }
    }
}
