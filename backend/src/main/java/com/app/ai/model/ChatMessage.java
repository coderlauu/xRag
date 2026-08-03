package com.app.ai.model;

public sealed interface ChatMessage
        permits SystemMessage, UserMessage, AssistantMessage, ToolResultMessage {
}
