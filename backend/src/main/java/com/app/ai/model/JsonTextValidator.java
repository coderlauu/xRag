package com.app.ai.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class JsonTextValidator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private JsonTextValidator() {
    }

    static String requireJson(String field, String value) {
        JsonNode node = parse(field, value);
        if (node.isMissingNode()) {
            throw new IllegalArgumentException(field + " must contain JSON");
        }
        return value;
    }

    static String requireJsonObject(String field, String value) {
        if (!parse(field, value).isObject()) {
            throw new IllegalArgumentException(field + " must be a JSON object");
        }
        return value;
    }

    private static JsonNode parse(String field, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must contain JSON");
        }
        try {
            return JSON.readTree(value);
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException(field + " must contain valid JSON");
        }
    }
}
