package com.app.qa.config;

import java.time.Duration;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.qa.generation")
public class QaGenerationProperties implements InitializingBean {

    private static final Duration SSE_COMPLETION_MARGIN = Duration.ofSeconds(15);

    private int maxConcurrency = 4;
    private int queueCapacity = 32;
    private Duration queueWait = Duration.ofSeconds(30);
    private Duration firstByteTimeout = Duration.ofSeconds(15);
    private Duration modelDeadline = Duration.ofSeconds(120);
    private Duration sseTimeout = Duration.ofSeconds(165);
    private int pushThreads = 4;
    private int pushQueueCapacity = 256;

    @Override
    public void afterPropertiesSet() {
        if (maxConcurrency < 1) {
            throw new IllegalArgumentException("app.qa.generation.max-concurrency must be positive");
        }
        if (queueCapacity < 1) {
            throw new IllegalArgumentException("app.qa.generation.queue-capacity must be positive");
        }
        if (pushThreads < 1) {
            throw new IllegalArgumentException("app.qa.generation.push-threads must be positive");
        }
        if (pushQueueCapacity < 1) {
            throw new IllegalArgumentException("app.qa.generation.push-queue-capacity must be positive");
        }
        if (queueCapacity < maxConcurrency) {
            throw new IllegalArgumentException("app.qa.generation.queue-capacity (" + queueCapacity
                    + ") must be greater than or equal to app.qa.generation.max-concurrency ("
                    + maxConcurrency + ")");
        }
        if (pushQueueCapacity < queueCapacity) {
            throw new IllegalArgumentException("app.qa.generation.push-queue-capacity (" + pushQueueCapacity
                    + ") must be greater than or equal to app.qa.generation.queue-capacity ("
                    + queueCapacity + ")");
        }
        requirePositive(queueWait, "queue-wait");
        requirePositive(firstByteTimeout, "first-byte-timeout");
        requirePositive(modelDeadline, "model-deadline");
        requirePositive(sseTimeout, "sse-timeout");
        if (firstByteTimeout.compareTo(modelDeadline) >= 0) {
            throw new IllegalArgumentException("app.qa.generation.first-byte-timeout (" + firstByteTimeout
                    + ") must be less than app.qa.generation.model-deadline (" + modelDeadline + ")");
        }
        var minimumSseTimeout = queueWait.plus(modelDeadline).plus(SSE_COMPLETION_MARGIN);
        if (sseTimeout.compareTo(minimumSseTimeout) < 0) {
            throw new IllegalArgumentException("app.qa.generation.sse-timeout (" + sseTimeout
                    + ") must be greater than or equal to queue-wait + model-deadline + "
                    + SSE_COMPLETION_MARGIN + " (" + minimumSseTimeout + ")");
        }
    }

    private static void requirePositive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException("app.qa.generation." + property + " must be positive");
        }
    }

    public int getMaxConcurrency() {
        return maxConcurrency;
    }

    public void setMaxConcurrency(int maxConcurrency) {
        this.maxConcurrency = maxConcurrency;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public Duration getQueueWait() {
        return queueWait;
    }

    public void setQueueWait(Duration queueWait) {
        this.queueWait = queueWait;
    }

    public Duration getFirstByteTimeout() {
        return firstByteTimeout;
    }

    public void setFirstByteTimeout(Duration firstByteTimeout) {
        this.firstByteTimeout = firstByteTimeout;
    }

    public Duration getModelDeadline() {
        return modelDeadline;
    }

    public void setModelDeadline(Duration modelDeadline) {
        this.modelDeadline = modelDeadline;
    }

    public Duration getSseTimeout() {
        return sseTimeout;
    }

    public void setSseTimeout(Duration sseTimeout) {
        this.sseTimeout = sseTimeout;
    }

    public int getPushThreads() {
        return pushThreads;
    }

    public void setPushThreads(int pushThreads) {
        this.pushThreads = pushThreads;
    }

    public int getPushQueueCapacity() {
        return pushQueueCapacity;
    }

    public void setPushQueueCapacity(int pushQueueCapacity) {
        this.pushQueueCapacity = pushQueueCapacity;
    }
}
