package com.app.ai.runtime;

import java.time.Duration;

@FunctionalInterface
public interface Sleeper {

    void sleep(Duration duration) throws InterruptedException;

    static Sleeper threadSleep() {
        return duration -> Thread.sleep(duration.toMillis(), duration.toNanosPart() % 1_000_000);
    }
}
