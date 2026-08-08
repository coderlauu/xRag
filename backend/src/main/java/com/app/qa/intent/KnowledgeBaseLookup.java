package com.app.qa.intent;

@FunctionalInterface
public interface KnowledgeBaseLookup {

    boolean isAvailable(long knowledgeBaseId);
}
