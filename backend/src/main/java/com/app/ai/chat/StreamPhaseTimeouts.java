package com.app.ai.chat;

import java.time.Duration;

record StreamPhaseTimeouts(
        Duration connect,
        Duration responseHeaders,
        Duration firstUpstreamEvent,
        Duration firstMeaningfulChunk,
        Duration idle) {

    StreamPhaseTimeouts {
        requirePositive(connect, "connect");
        requirePositive(responseHeaders, "responseHeaders");
        requirePositive(firstUpstreamEvent, "firstUpstreamEvent");
        requirePositive(firstMeaningfulChunk, "firstMeaningfulChunk");
        requirePositive(idle, "idle");
    }

    static StreamPhaseTimeouts defaults() {
        return new StreamPhaseTimeouts(
                Duration.ofSeconds(3),
                Duration.ofSeconds(10),
                Duration.ofSeconds(15),
                Duration.ofSeconds(20),
                Duration.ofSeconds(30));
    }

    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
