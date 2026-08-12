package com.app.qa.generation;

import java.util.UUID;

@FunctionalInterface
interface AssistantAnswerStore {

    void save(AnswerWrite answer);

    record AnswerWrite(
            UUID messageId,
            UUID conversationId,
            UUID turnId,
            long sequenceNo,
            String content,
            boolean incomplete) {}
}
