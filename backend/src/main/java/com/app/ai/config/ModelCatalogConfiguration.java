package com.app.ai.config;

import com.app.ai.model.ModelCatalogSnapshot;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiModelsProperties.class)
public class ModelCatalogConfiguration {

    @Bean
    ModelCatalogSnapshot modelCatalogSnapshot(AiModelsProperties properties, Environment environment) {
        return ModelCatalogFactory.create(properties, environment::getProperty);
    }
}
