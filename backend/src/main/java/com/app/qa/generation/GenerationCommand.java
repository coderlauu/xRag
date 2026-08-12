package com.app.qa.generation;

import com.app.qa.prompt.GenerationPromptAssembler;
import java.util.UUID;

public record GenerationCommand(
        UUID conversationId,
        UUID turnId,
        UUID userMessageId,
        GenerationPromptAssembler.Material promptMaterial) {

    public GenerationCommand {
        if (conversationId == null || turnId == null || userMessageId == null || promptMaterial == null) {
            throw new IllegalArgumentException("generation command fields must not be null");
        }
    }
}
