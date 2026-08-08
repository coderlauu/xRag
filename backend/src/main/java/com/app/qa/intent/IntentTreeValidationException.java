package com.app.qa.intent;

public final class IntentTreeValidationException extends RuntimeException {

    private final Kind kind;

    public IntentTreeValidationException(String message) {
        this(Kind.CONSTRAINT, message);
    }

    IntentTreeValidationException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    public enum Kind {
        CONSTRAINT,
        NOT_FOUND,
        VIRTUAL_ROOT
    }
}
