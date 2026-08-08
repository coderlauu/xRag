package com.app.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ProviderConfigurationStatus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;

class ModelCatalogConfigurationTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ModelCatalogConfiguration.class)
            .withPropertyValues(
                    "app.ai.policy-version=test-v1",
                    "app.ai.providers.provider-a.adapter=openai-compatible",
                    "app.ai.providers.provider-a.display-name=Provider A",
                    "app.ai.providers.provider-a.base-url=https://example.test/v1",
                    "app.ai.providers.provider-a.secret-property=UNSET_AI_SECRET",
                    "app.ai.providers.provider-a.data-region=CN_TEST",
                    "app.ai.providers.provider-a.quota-domain=quota-a",
                    "app.ai.deployments.chat-a.provider-id=provider-a",
                    "app.ai.deployments.chat-a.upstream-model=chat-v1",
                    "app.ai.deployments.chat-a.model-identity=chat-v1",
                    "app.ai.deployments.chat-a.capabilities[0]=CHAT",
                    "app.ai.deployments.chat-a.tiers[0]=BALANCED",
                    "app.ai.deployments.chat-a.chat.sampling-by-tier.BALANCED.temperature=0.1",
                    "app.ai.deployments.chat-a.chat.sampling-by-tier.BALANCED.top-p=0.3",
                    "app.ai.deployments.chat-a.default-candidate=true",
                    "app.ai.deployments.chat-a.priority=10",
                    "app.ai.deployments.chat-a.enabled=true");

    @Test
    void aiModelsProperties绑定并产生单一快照Bean() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ModelCatalogSnapshot.class);
            assertThat(context.getBean(ModelCatalogSnapshot.class).providers().values())
                    .allSatisfy(provider -> assertThat(provider.configurationStatus())
                            .isEqualTo(ProviderConfigurationStatus.UNCONFIGURED));
        });
    }

    @Test
    void aiModelsYaml能从Classpath绑定为完整快照() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(ModelCatalogConfiguration.class)
                .withPropertyValues("spring.config.import=classpath:ai-models.yml")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var snapshot = context.getBean(ModelCatalogSnapshot.class);
                    assertThat(snapshot.policyVersion()).isEqualTo("phase2-v1");
                    assertThat(snapshot.providers()).hasSize(2);
                    assertThat(snapshot.deployments()).hasSize(4);
                    // Rerank deployment 默认关闭：打开它必须与注入 DASHSCOPE_API_KEY 同时做，
                    // 只开一半会让重排以 AUTHENTICATION 直接失败（architecture.md §11）。
                    var rerank = snapshot.deployments().get(
                            new com.app.ai.model.DeploymentId("aliyun-qwen3-rerank-cn"));
                    assertThat(rerank).isNotNull();
                    assertThat(rerank.enabled()).isFalse();
                    assertThat(rerank.rerank().maxDocuments()).isEqualTo(100);
                });
    }
}
