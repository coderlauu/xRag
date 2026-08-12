package com.app.qa.generation;

public final class ToolsNotSettledException extends RuntimeException {
    public ToolsNotSettledException() {
        super("generation requires all tools to be settled");
    }
}
