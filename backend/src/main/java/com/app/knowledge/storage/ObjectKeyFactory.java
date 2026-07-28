package com.app.knowledge.storage;

import java.text.Normalizer;
import java.util.Locale;

/** 生成对控制台友好、同时不会因业务改名而变化的对象 key。 */
public final class ObjectKeyFactory {

    private static final int ALIAS_MAX_LENGTH = 48;
    private static final int FILE_NAME_MAX_LENGTH = 180;

    private ObjectKeyFactory() {}

    public static String storageAlias(String knowledgeBaseName) {
        String alias = normalize(knowledgeBaseName)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (alias.isBlank()) {
            return "knowledge";
        }
        return truncate(alias, ALIAS_MAX_LENGTH).replaceAll("-+$", "");
    }

    public static String versionKey(long kbId, String storageAlias, String documentObjectId,
            String contentVersion, String originalFilename) {
        return "knowledge-bases/%d-%s/documents/%s/versions/%s/%s".formatted(
                kbId,
                safeSegment(storageAlias, "knowledge"),
                safeSegment(documentObjectId, "document"),
                safeSegment(contentVersion, "version"),
                safeFilename(originalFilename));
    }

    private static String safeFilename(String originalFilename) {
        String normalized = normalize(originalFilename).replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String basename = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        String safe = basename
                .replaceAll("[^\\p{L}\\p{N}._-]+", "-")
                .replaceAll("(^[.-]+|[.-]+$)", "");
        if (safe.isBlank()) {
            safe = "document";
        }
        return truncate(safe, FILE_NAME_MAX_LENGTH);
    }

    private static String safeSegment(String value, String fallback) {
        String safe = normalize(value)
                .replaceAll("[^\\p{L}\\p{N}._-]+", "-")
                .replaceAll("(^[.-]+|[.-]+$)", "");
        return safe.isBlank() ? fallback : safe;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFKC);
    }

    private static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
