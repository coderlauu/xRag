package com.app.ai.model;

import java.time.Duration;
import java.util.Objects;

public record AiRuntimePolicy(
        CapabilityLimits chat,
        CapabilityLimits streamChat,
        CapabilityLimits embedding,
        CapabilityLimits rerank,
        StreamLimits stream,
        BreakerLimits breaker) {

    public AiRuntimePolicy {
        Objects.requireNonNull(chat);
        Objects.requireNonNull(streamChat);
        Objects.requireNonNull(embedding);
        Objects.requireNonNull(rerank);
        Objects.requireNonNull(stream);
        Objects.requireNonNull(breaker);
    }

    public record CapabilityLimits(
            Duration totalTimeout,
            int maxAttemptsPerWorkUnit,
            int maxSameDeploymentAttempts) {
    }

    public record StreamLimits(
            int probeMaxEvents,
            int probeMaxBytes,
            int sseEventMaxBytes) {
    }

    public record BreakerLimits(
            int slidingWindowSize,
            int minimumNumberOfCalls,
            double failureRateThreshold,
            double slowCallRateThreshold,
            Duration openWaitDuration,
            int halfOpenPermits,
            Duration halfOpenMaxWait) {
    }
}
