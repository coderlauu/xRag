package com.app.qa.conversation;

public final class IdempotencyKeyReusedException extends RuntimeException {
    public IdempotencyKeyReusedException() {
        super("clientRequestId was already used with different message content");
    }
}
