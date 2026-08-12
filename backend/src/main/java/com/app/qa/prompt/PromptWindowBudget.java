package com.app.qa.prompt;

public record PromptWindowBudget(
        int modelWindowBytes,
        int maxOutputBytes,
        int inputBudgetBytes) {

    public PromptWindowBudget {
        if (modelWindowBytes < 1) {
            throw new IllegalArgumentException("model window must be positive");
        }
        if (maxOutputBytes < 0) {
            throw new IllegalArgumentException("max output must not be negative");
        }
        if (inputBudgetBytes < 1) {
            throw new IllegalArgumentException("input budget must be positive");
        }
        if ((long) inputBudgetBytes + maxOutputBytes > modelWindowBytes) {
            throw new IllegalArgumentException("input budget + max output must not exceed model window");
        }
        long safeInputLimit = ((long) modelWindowBytes - maxOutputBytes) * 4 / 5;
        if (inputBudgetBytes > safeInputLimit) {
            throw new IllegalArgumentException(
                    "input budget must preserve a 20% safety margin; maximum is " + safeInputLimit);
        }
    }

    public static PromptWindowBudget fixedInputBytes(int inputBudgetBytes) {
        if (inputBudgetBytes < 1) {
            throw new IllegalArgumentException("input budget must be positive");
        }
        int window = Math.toIntExact(((long) inputBudgetBytes * 5 + 3) / 4);
        return new PromptWindowBudget(window, 0, inputBudgetBytes);
    }
}
