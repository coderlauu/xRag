package com.app.qa.generation;

import java.util.Objects;
import java.util.UUID;

public record GenerationTaskId(UUID value) {

    public GenerationTaskId {
        Objects.requireNonNull(value, "value");
    }
}
