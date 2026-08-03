package com.app.ai.provider;

public final class IdentifierMasker {

    private IdentifierMasker() {
    }

    public static String mask(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        if (raw.length() <= 8) {
            return "****";
        }
        return raw.substring(0, 4) + "****" + raw.substring(raw.length() - 4);
    }
}
