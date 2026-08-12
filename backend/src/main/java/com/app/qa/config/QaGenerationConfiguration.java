package com.app.qa.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QaGenerationProperties.class)
public class QaGenerationConfiguration {

    @Bean("qaGenerationExecutor")
    ThreadPoolTaskExecutor qaGenerationExecutor(QaGenerationProperties properties) {
        return executor(
                properties.getMaxConcurrency(),
                properties.getQueueCapacity(),
                "qa-generation-");
    }

    @Bean("qaGenerationPushExecutor")
    ThreadPoolTaskExecutor qaGenerationPushExecutor(QaGenerationProperties properties) {
        return executor(
                properties.getPushThreads(),
                properties.getPushQueueCapacity(),
                "qa-generation-push-");
    }

    @Bean
    QaGenerationRequirements qaGenerationRequirements(
            QaGenerationProperties properties,
            @org.springframework.beans.factory.annotation.Qualifier("qaGenerationExecutor")
                    ThreadPoolTaskExecutor generationExecutor) {
        return new QaGenerationRequirements(properties, generationExecutor);
    }

    private static ThreadPoolTaskExecutor executor(int threads, int queueCapacity, String prefix) {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(prefix);
        return executor;
    }
}
