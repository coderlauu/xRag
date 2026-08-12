package com.app.qa.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

final class QaGenerationRequirements implements SmartInitializingSingleton {

    private final QaGenerationProperties properties;
    private final ThreadPoolTaskExecutor generationExecutor;

    QaGenerationRequirements(
            QaGenerationProperties properties,
            ThreadPoolTaskExecutor generationExecutor) {
        this.properties = properties;
        this.generationExecutor = generationExecutor;
    }

    @Override
    public void afterSingletonsInstantiated() {
        var configured = properties.getMaxConcurrency();
        var core = generationExecutor.getCorePoolSize();
        var maximum = generationExecutor.getMaxPoolSize();
        if (configured != core || configured != maximum) {
            throw new IllegalStateException("app.qa.generation.max-concurrency (" + configured
                    + ") must equal qaGenerationExecutor core/max pool size ("
                    + core + "/" + maximum + ")");
        }
    }
}
