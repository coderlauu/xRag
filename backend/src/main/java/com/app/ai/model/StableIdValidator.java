package com.app.ai.model;

import java.util.regex.Pattern;

final class StableIdValidator {

    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]*");

    private StableIdValidator() {
    }

    static String require(String field, String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength || !FORMAT.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "%s must match %s and contain at most %d characters".formatted(field, FORMAT, maxLength));
        }
        return value;
    }
}
