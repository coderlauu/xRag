package com.app.ai.runtime;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

@FunctionalInterface
public interface JitterSource {

    Duration choose(Duration upperBound);

    static JitterSource random() {
        return upperBound -> Duration.ofNanos(
                ThreadLocalRandom.current().nextLong(upperBound.toNanos() + 1));
    }
}
