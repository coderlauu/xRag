package com.app.ai.model;

public enum ChatFinishReason {
    STOP,
    LENGTH,
    TOOL_CALLS,
    CONTENT_FILTER,
    UNKNOWN
}
