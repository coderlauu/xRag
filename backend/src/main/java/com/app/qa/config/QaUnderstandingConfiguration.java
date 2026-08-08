package com.app.qa.config;

import com.app.qa.prompt.PromptTemplateCatalog;
import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QaUnderstandingProperties.class)
public class QaUnderstandingConfiguration {

    @Bean
    PromptTemplateCatalog qaPromptTemplateCatalog(QaUnderstandingProperties properties) {
        return PromptTemplateCatalog.fromClasspath(properties.getMaxIntentPromptBytes());
    }

    @Bean("qaIntentScoringExecutor")
    ThreadPoolTaskExecutor qaIntentScoringExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(3);
        executor.setMaxPoolSize(3);
        executor.setQueueCapacity(3);
        executor.setThreadNamePrefix("qa-intent-score-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        return executor;
    }
}
