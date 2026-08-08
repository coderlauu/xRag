package com.app.qa.rewrite;

import com.app.qa.model.RewriteMode;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RewriteResult(
        String normalizedQuestion,
        String rewrittenQuestion,
        List<String> subQuestions,
        RewriteMode mode,
        RewriteDisposition disposition,
        String degradationCode,
        UUID logicalCallId) {

    public RewriteResult {
        Objects.requireNonNull(normalizedQuestion, "normalizedQuestion");
        Objects.requireNonNull(rewrittenQuestion, "rewrittenQuestion");
        subQuestions = List.copyOf(subQuestions);
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(disposition, "disposition");
    }
}
