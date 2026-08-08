package com.app.qa.conversation;

@FunctionalInterface
public interface TurnProcessor {

    void process(ConversationPersistenceService.TurnSubmission submission);
}
