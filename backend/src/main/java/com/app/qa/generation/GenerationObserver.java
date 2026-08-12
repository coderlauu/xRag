package com.app.qa.generation;

@FunctionalInterface
public interface GenerationObserver {
    void onEvent(GenerationEvent event);
}
