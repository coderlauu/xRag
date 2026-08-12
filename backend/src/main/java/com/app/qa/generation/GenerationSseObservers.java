package com.app.qa.generation;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface GenerationSseObservers {
    GenerationObserver create(SseEmitter emitter);
}
