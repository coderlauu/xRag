package com.app.qa.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashMap;

public final class UntrustedPromptCodec {

    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Kind {
        USER_QUESTION,
        RECENT_HISTORY,
        INTENT_HINT,
        RETRIEVAL_EVIDENCE,
        TOOL_RESULT,
        OLD_SUMMARY
    }

    public String encode(Kind kind, String id, String value) {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }

        String normalized = normalize(value);
        var encoded = new LinkedHashMap<String, Object>();
        encoded.put("kind", kind.name());
        encoded.put("id", id);
        encoded.put("utf8Bytes", normalized.getBytes(StandardCharsets.UTF_8).length);
        encoded.put("value", normalized);
        try {
            return JSON.writeValueAsString(encoded);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("cannot encode prompt value", exception);
        }
    }

    private static String normalize(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace("\r\n", "\n")
                .replace('\r', '\n');
        var clean = new StringBuilder(normalized.length());
        normalized.codePoints()
                .filter(codePoint -> codePoint == '\n' || !Character.isISOControl(codePoint))
                .forEach(clean::appendCodePoint);
        return clean.toString();
    }
}
