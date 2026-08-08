package com.app.qa.prompt;

import java.util.Set;

public enum QaPrompt {
    SUMMARY_SYSTEM("summary-system.md", Set.of()),
    SUMMARY_USER("summary-user.md", Set.of("previousSummary", "history")),
    REWRITE_SYSTEM("rewrite-system.md", Set.of()),
    REWRITE_USER("rewrite-user.md", Set.of("summary", "history", "question")),
    INTENT_SCORE_SYSTEM("intent-score-system.md", Set.of()),
    INTENT_SCORE_USER("intent-score-user.md", Set.of("question", "intentCatalog")),
    AMBIGUITY_CONFIRM_SYSTEM("ambiguity-confirm-system.md", Set.of()),
    AMBIGUITY_CONFIRM_USER("ambiguity-confirm-user.md", Set.of("question", "candidates"));

    private static final String ROOT = "prompts/qa/3a/";

    private final String fileName;
    private final Set<String> requiredPlaceholders;

    QaPrompt(String fileName, Set<String> requiredPlaceholders) {
        this.fileName = fileName;
        this.requiredPlaceholders = Set.copyOf(requiredPlaceholders);
    }

    public String classpathLocation() {
        return "classpath:" + ROOT + fileName;
    }

    String classpathPath() {
        return ROOT + fileName;
    }

    public Set<String> requiredPlaceholders() {
        return requiredPlaceholders;
    }
}
