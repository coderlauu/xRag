package com.app.ai.config;

import com.app.ai.breaker.ModelBreakerRegistry;
import com.app.ai.diagnostic.DiagnosticRecorder;
import com.app.ai.model.Capability;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderConfigurationStatus;
import com.app.ai.model.ProviderId;
import com.app.ai.model.RerankClient;
import com.app.ai.provider.OpenAiCompatibleRerankProvider;
import com.app.ai.provider.RerankProvider;
import com.app.ai.rerank.RoutedRerankClient;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 装配 Rerank 入口。与 Embedding 一样只为密钥真的解析出来的 Provider 建适配器，缺密钥不阻塞启动。
 *
 * <p>注意两者的**缺密钥语义不同**：Embedding 缺密钥时调用以 {@code NO_ELIGIBLE_MODEL} 失败；
 * Rerank 则要看目录——目录里根本没有 RERANK deployment 是允许的产品状态（Noop 降级），
 * 但目录里声明了却缺密钥是配置故障，调用会直接失败。见 architecture.md §11。
 */
@Configuration(proxyBeanMethods = false)
public class RerankClientConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(RerankClientConfiguration.class);

    @Bean
    public RerankClient rerankClient(
            ModelCatalogSnapshot catalog,
            ModelBreakerRegistry breakers,
            DiagnosticRecorder diagnostics,
            Environment environment) {
        Map<ProviderId, RerankProvider> providers = rerankProviders(catalog, environment);
        if (providers.isEmpty()) {
            LOGGER.info("没有已配置密钥的 Rerank Provider；重排将按目录状态降级为 Noop 或直接失败。");
        }
        return new RoutedRerankClient(catalog, providers, breakers, diagnostics);
    }

    private static Map<ProviderId, RerankProvider> rerankProviders(
            ModelCatalogSnapshot catalog, Environment environment) {
        var providers = new LinkedHashMap<ProviderId, RerankProvider>();
        for (ModelDeployment deployment : catalog.deployments().values()) {
            if (!deployment.capabilities().contains(Capability.RERANK)) {
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
            providers.put(provider.id(), new OpenAiCompatibleRerankProvider(
                    provider.baseUrl(), apiKey));
            LOGGER.info("Rerank Provider 已装配：{} @ {}", provider.id().value(), provider.baseUrl());
        }
        return Map.copyOf(providers);
    }
}
