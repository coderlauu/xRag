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
import com.app.ai.provider.DashScopeNativeRerankProvider;
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

    /** 与 `ai-models.yml` 里 `adapter` / `rerank.adapter` 的取值一一对应。 */
    private static final String OPENAI_COMPATIBLE_ADAPTER = "openai-compatible";
    private static final String DASHSCOPE_NATIVE_ADAPTER = "dashscope-native";

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
        var adapters = new LinkedHashMap<ProviderId, String>();
        for (ModelDeployment deployment : catalog.deployments().values()) {
            if (!deployment.capabilities().contains(Capability.RERANK)) {
                continue;
            }
            ModelProvider provider = catalog.providers().get(deployment.providerId());
            if (provider.configurationStatus() != ProviderConfigurationStatus.CONFIGURED) {
                continue;
            }
            String adapter = deployment.rerank().adapter();
            String existing = adapters.get(provider.id());
            if (existing != null) {
                // RoutedRerankClient 按 providerId 取适配器，同一 Provider 只能有一个 Rerank 协议。
                // 静默取第一个会让第二个 deployment 用错协议去打上游，症状是难解释的 404/400。
                if (!existing.equals(adapter)) {
                    throw new IllegalStateException(
                            "Provider " + provider.id().value() + " 的多个 RERANK deployment 声明了不同的"
                                    + " adapter（" + existing + " 与 " + adapter + "）；当前调度按 Provider"
                                    + "解析 Rerank 适配器，无法同时装配。");
                }
                continue;
            }
            String apiKey = environment.getProperty(provider.secretProperty());
            if (apiKey == null || apiKey.isBlank()) {
                continue;
            }
            adapters.put(provider.id(), adapter);
            providers.put(provider.id(), createAdapter(adapter, provider, apiKey));
            LOGGER.info("Rerank Provider 已装配：{} @ {}（adapter={}）",
                    provider.id().value(), provider.baseUrl(), adapter);
        }
        return Map.copyOf(providers);
    }

    /**
     * 目录层不校验 adapter 取值（与 {@code ModelProvider#adapter()} 一致），认不认得出在这里决定。
     * 未知取值必须启动失败：静默回落到某个默认适配器会让"配错协议"表现成运行期难解释的上游报错。
     */
    private static RerankProvider createAdapter(
            String adapter, ModelProvider provider, String apiKey) {
        return switch (adapter) {
            case OPENAI_COMPATIBLE_ADAPTER ->
                    new OpenAiCompatibleRerankProvider(provider.baseUrl(), apiKey);
            case DASHSCOPE_NATIVE_ADAPTER ->
                    new DashScopeNativeRerankProvider(provider.baseUrl(), apiKey);
            default -> throw new IllegalStateException(
                    "Provider " + provider.id().value() + " 的 RERANK adapter 无法识别：" + adapter
                            + "；已支持 " + OPENAI_COMPATIBLE_ADAPTER + " 与 " + DASHSCOPE_NATIVE_ADAPTER);
        };
    }
}
