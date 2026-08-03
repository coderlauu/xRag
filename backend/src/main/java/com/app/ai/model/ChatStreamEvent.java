package com.app.ai.model;

import java.util.Objects;

public sealed interface ChatStreamEvent
        permits ChatStreamEvent.StreamStarted,
                ChatStreamEvent.TextDelta,
                ChatStreamEvent.ToolCallDelta,
                ChatStreamEvent.RefusalDelta,
                ChatStreamEvent.Completed,
                ChatStreamEvent.StreamFailed {

    record StreamStarted(ExecutionInfo execution) implements ChatStreamEvent {
        public StreamStarted {
            Objects.requireNonNull(execution);
        }
    }

    record TextDelta(String text) implements ChatStreamEvent {
        public TextDelta {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("text must not be empty");
            }
        }
    }

    record ToolCallDelta(String callId, int index, String name, String argumentsFragment)
            implements ChatStreamEvent {
        public ToolCallDelta {
            if (callId == null || callId.isBlank() || index < 0
                    || name == null || name.isBlank() || argumentsFragment == null) {
                throw new IllegalArgumentException("tool call delta fields are invalid");
            }
        }
    }

    record RefusalDelta(String text) implements ChatStreamEvent {
        public RefusalDelta {
            if (text == null || text.isEmpty()) {
                throw new IllegalArgumentException("text must not be empty");
            }
        }
    }

    record Completed(ChatFinishReason finishReason, TokenUsage usage) implements ChatStreamEvent {
        public Completed {
            Objects.requireNonNull(finishReason);
        }
    }

    record StreamFailed(TerminalCause cause, String message, boolean incomplete)
            implements ChatStreamEvent {
        public StreamFailed {
            Objects.requireNonNull(cause);
            if (message == null || message.isBlank()) {
                throw new IllegalArgumentException("message must not be blank");
            }
            if (!incomplete) {
                throw new IllegalArgumentException("stream failure must be incomplete");
            }
        }
    }
}
