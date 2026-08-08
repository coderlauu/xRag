package com.app.qa.prompt;

import com.app.qa.model.IntentNodeType;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record IntentCatalogEntry(
        UUID id,
        IntentNodeType type,
        String fullPath,
        String description,
        List<String> examples) {

    public IntentCatalogEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        if (type == IntentNodeType.GROUP) {
            throw new IllegalArgumentException("GROUP nodes do not belong in the executable intent catalog");
        }
        if (fullPath == null || fullPath.isBlank()) {
            throw new IllegalArgumentException("fullPath must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        examples = List.copyOf(examples);
        if (examples.isEmpty() || examples.stream().anyMatch(example -> example == null || example.isBlank())) {
            throw new IllegalArgumentException("examples must contain non-blank values");
        }
    }
}
