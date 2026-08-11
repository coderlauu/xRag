package com.app.ai.config;

import com.app.ai.breaker.ModelBreakerRegistry;
import com.app.ai.chat.RoutingChatClient;
import com.app.ai.chat.RoutingStreamingChatClient;
import com.app.ai.diagnostic.DiagnosticRecorder;
import com.app.ai.model.Capability;
import com.app.ai.model.ChatClient;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderConfigurationStatus;
import com.app.ai.model.ProviderId;
import com.app.ai.model.StreamingChatClient;
import com.app.ai.provider.ChatProvider;
import com.app.ai.provider.OpenAiCompatibleChatProvider;
import com.app.ai.provider.StreamingChatProvider;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 装配同步与流式 Chat 入口。与 {@link EmbeddingClientConfiguration}、
 * {@link RerankClientConfiguration} 同形：只为密钥真的解析出来的 Provider 建适配器，
 * 缺密钥不阻塞启动，调用时以 {@code NO_ELIGIBLE_MODEL} 失败。
 *
 * <p>本类补的是工单 15 之后一直缺的接线：{@link RoutingChatClient} 与
 * {@link RoutingStreamingChatClient} 早已实现且有单元测试，但没有任何 {@code @Configuration}
 * 把它们暴露成 bean——Phase 2 没有 Chat 的业务消费者（问答链路属于 Phase 3），缺失一直没有
 * 被发现。工单 23 的真实准入要走完整调度链路（路由、熔断、诊断写入），不能绕过它直接打
 * Provider 适配器，所以准入之前必须先补上装配。
 *
 * <p>{@link OpenAiCompatibleChatProvider} 同时实现 {@link ChatProvider} 与
 * {@link StreamingChatProvider}，两个 bean 各自持有一份实例：适配器内部同时维护同步的
 * Apache HttpClient 和流式的 WebClient 连接池，共享一份实例会让两条链路的连接生命周期
 * 互相牵连，而分开建的代价只是多一组空闲连接。
 */
@Configuration(proxyBeanMethods = false)
public class ChatClientConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatClientConfiguration.class);

    @Bean
    public ChatClient chatClient(
            ModelCatalogSnapshot catalog,
            ModelBreakerRegistry breakers,
            DiagnosticRecorder diagnostics,
            Environment environment) {
        Map<ProviderId, ChatProvider> providers =
                new LinkedHashMap<>(chatProviders(catalog, environment, Capability.CHAT));
        if (providers.isEmpty()) {
            LOGGER.info("没有已配置密钥的 Chat Provider；同步 Chat 调用将以 NO_ELIGIBLE_MODEL 失败。");
        }
        return new RoutingChatClient(catalog, providers, breakers, diagnostics);
    }

    @Bean
    public StreamingChatClient streamingChatClient(
            ModelCatalogSnapshot catalog,
            ModelBreakerRegistry breakers,
            DiagnosticRecorder diagnostics,
            Environment environment) {
        Map<ProviderId, StreamingChatProvider> providers =
                new LinkedHashMap<>(chatProviders(catalog, environment, Capability.STREAM_CHAT));
        if (providers.isEmpty()) {
            LOGGER.info("没有已配置密钥的流式 Chat Provider；流式调用将以 NO_ELIGIBLE_MODEL 失败。");
        }
        return new RoutingStreamingChatClient(catalog, providers, breakers, diagnostics);
    }

    private static Map<ProviderId, OpenAiCompatibleChatProvider> chatProviders(
            ModelCatalogSnapshot catalog, Environment environment, Capability capability) {
        var providers = new LinkedHashMap<ProviderId, OpenAiCompatibleChatProvider>();
        for (ModelDeployment deployment : catalog.deployments().values()) {
            if (!deployment.capabilities().contains(capability)) {
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
            providers.put(provider.id(), new OpenAiCompatibleChatProvider(
                    provider.baseUrl(), apiKey));
            LOGGER.info("{} Provider 已装配：{} @ {}",
                    capability, provider.id().value(), provider.baseUrl());
        }
        return Map.copyOf(providers);
    }
}
