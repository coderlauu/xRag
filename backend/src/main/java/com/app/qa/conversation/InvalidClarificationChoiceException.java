package com.app.qa.conversation;

public final class InvalidClarificationChoiceException extends RuntimeException {

    public InvalidClarificationChoiceException() {
        super("clarification choice is forged, stale, or belongs to another conversation");
    }
}
