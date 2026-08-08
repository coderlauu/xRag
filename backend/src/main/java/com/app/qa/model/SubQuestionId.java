package com.app.qa.model;

import java.util.Objects;
import java.util.UUID;

public record SubQuestionId(UUID value) {
    public SubQuestionId {
        Objects.requireNonNull(value, "subQuestionId");
    }
}
