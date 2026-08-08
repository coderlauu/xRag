package com.app.qa.prompt;

public final class PromptBudgetExceededException extends RuntimeException {

    public PromptBudgetExceededException(int actualBytes, int maxBytes) {
        super("rendered prompt uses " + actualBytes + " UTF-8 bytes, exceeding limit " + maxBytes);
    }
}
