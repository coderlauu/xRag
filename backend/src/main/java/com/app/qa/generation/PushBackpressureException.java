package com.app.qa.generation;

public final class PushBackpressureException extends RuntimeException {
    public PushBackpressureException() {
        super("generation push mailbox is full");
    }
}
