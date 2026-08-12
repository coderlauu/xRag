package com.app.qa.generation;

public final class GenerationQueueFullException extends RuntimeException {
    public GenerationQueueFullException() {
        super("generation queue is full");
    }
}
