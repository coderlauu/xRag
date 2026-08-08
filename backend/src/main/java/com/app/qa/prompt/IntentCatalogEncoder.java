package com.app.qa.prompt;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public final class IntentCatalogEncoder {

    private final ObjectMapper objectMapper;

    public IntentCatalogEncoder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(List<IntentCatalogEntry> entries) {
        var sorted = entries.stream()
                .sorted(Comparator.comparing(IntentCatalogEntry::id))
                .toList();
        try {
            return objectMapper.writeValueAsString(sorted);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot encode intent catalog", exception);
        }
    }
}
