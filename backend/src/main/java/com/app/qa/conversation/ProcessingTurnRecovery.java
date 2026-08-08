package com.app.qa.conversation;

import com.app.qa.config.QaUnderstandingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
final class ProcessingTurnRecovery {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProcessingTurnRecovery.class);

    private final ConversationPersistenceService conversations;
    private final QaUnderstandingProperties properties;

    ProcessingTurnRecovery(
            ConversationPersistenceService conversations, QaUnderstandingProperties properties) {
        this.conversations = conversations;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTurns() {
        int recovered = conversations.failStaleProcessing(properties.getProcessingStaleAfter());
        if (recovered > 0) {
            LOGGER.warn("Recovered {} stale QA PROCESSING turns as PROCESS_INTERRUPTED", recovered);
        }
    }
}
