package com.app.qa.rewrite;

import java.util.Objects;

public record RewriteRequest(String summary, String recentHistory, String question) {
    public RewriteRequest {
        summary = Objects.requireNonNull(summary, "summary");
        recentHistory = Objects.requireNonNull(recentHistory, "recentHistory");
        Objects.requireNonNull(question, "question");
    }
}
