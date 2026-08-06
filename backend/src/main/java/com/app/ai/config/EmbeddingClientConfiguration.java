package com.app.ai.config;

import com.app.ai.breaker.ModelBreakerRegistry;
import com.app.ai.diagnostic.DiagnosticRecorder;
import com.app.ai.embedding.RoutedEmbeddingClient;
import com.app.ai.model.Capability;
import com.app.ai.model.EmbeddingClient;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderConfigurationStatus;
import com.app.ai.model.ProviderId;
import com.app.ai.provider.EmbeddingProvider;
import com.app.ai.provider.OpenAiCompatibleEmbeddingProvider;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Wires the scheduling layer's embedding entry point.
 *
 * <p>Only providers whose secret actually resolved become adapters. An identity whose provider is
 * unconfigured is already filtered out of the {@code RoutePlan}, so the call fails with
 * {@code NO_ELIGIBLE_MODEL} at use time rather than blocking application startup — the same
 * "dependency missing does not stop the app" rule Flyway and object storage already follow.
 */
@Configuration(proxyBeanMethods = false)
public class EmbeddingClientConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(EmbeddingClientConfiguration.class);

    @Bean
    public EmbeddingClient embeddingClient(
            ModelCatalogSnapshot catalog,
            ModelBreakerRegistry breakers,
            DiagnosticRecorder diagnostics,
            Environment environment,
            @Value("${app.embedding.batch-interval:200ms}") Duration minRequestInterval) {
        Map<ProviderId, EmbeddingProvider> providers = embeddingProviders(
                catalog, environment, minRequestInterval);
        if (providers.isEmpty()) {
            LOGGER.warn("没有已配置密钥的 Embedding Provider，向量化能力不可用（应用其余部分正常）。"
                    + "请为 ai-models.yml 中 EMBEDDING deployment 所属 Provider 设置其 secret-property"
                    + "对应的环境变量后重启。");
        }
        return new RoutedEmbeddingClient(catalog, providers, breakers, diagnostics);
    }

    private static Map<ProviderId, EmbeddingProvider> embeddingProviders(
            ModelCatalogSnapshot catalog, Environment environment, Duration minRequestInterval) {
        var providers = new LinkedHashMap<ProviderId, EmbeddingProvider>();
        for (ModelDeployment deployment : catalog.deployments().values()) {
            if (!deployment.capabilities().contains(Capability.EMBEDDING)) {
                continue;
            }
            ModelProvider provider = catalog.providers().get(deployment.providerId());
            if (provider.configurationStatus() != ProviderConfigurationStatus.CONFIGURED
                    || providers.containsKey(provider.id())) {
                continue;
            }
            String apiKey = environment.getProperty(provider.secretProperty());
            if (apiKey == null || apiKey.isBlank()) {
                continue;
            }
            providers.put(provider.id(), new OpenAiCompatibleEmbeddingProvider(
                    provider.baseUrl(), apiKey, minRequestInterval));
            LOGGER.info("Embedding Provider 已装配：{} @ {}，请求最小间隔 {}",
                    provider.id().value(), provider.baseUrl(), minRequestInterval);
        }
        return Map.copyOf(providers);
    }
}
