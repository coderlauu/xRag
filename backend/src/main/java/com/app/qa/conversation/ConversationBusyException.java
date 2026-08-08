package com.app.qa.conversation;

public final class ConversationBusyException extends RuntimeException {
    public ConversationBusyException() {
        super("conversation already has an active turn");
    }
}
