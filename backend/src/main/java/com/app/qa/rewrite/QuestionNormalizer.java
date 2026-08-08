package com.app.qa.rewrite;

import java.text.Normalizer;
import java.util.regex.Pattern;

public final class QuestionNormalizer {

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private QuestionNormalizer() {}

    public static String normalize(String question) {
        if (question == null) {
            throw new IllegalArgumentException("question must not be null");
        }
        String normalized = Normalizer.normalize(question, Normalizer.Form.NFKC);
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("question must not be blank after normalization");
        }
        return normalized;
    }
}
