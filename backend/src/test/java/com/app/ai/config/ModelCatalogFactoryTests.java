package com.app.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.ai.model.Capability;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ProviderConfigurationStatus;
import com.app.ai.model.ProviderId;
import com.app.ai.model.ServiceTier;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ByteArrayResource;

class ModelCatalogFactoryTests {

    @Test
    void cfg01_合法配置生成不可变脱敏快照() {
        var snapshot = ModelCatalogFactory.create(validProperties(), name -> "top-secret-value");

        assertThat(snapshot.policyVersion()).isEqualTo("test-v1");
        assertThat(snapshot.configFingerprint()).hasSize(64);
        assertThat(snapshot.providers()).containsKey(new ProviderId("provider-a"));
        assertThat(snapshot.deployments()).containsKey(new DeploymentId("chat-a"));
        assertThat(snapshot.deployments().get(new DeploymentId("chat-a"))
                        .chat().sampling(ServiceTier.BALANCED).temperature())
                .isEqualTo(0.1);
        assertThat(snapshot.toString()).doesNotContain("top-secret-value");
        assertThatThrownBy(() -> snapshot.providers().clear())
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> snapshot.deployments().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void cfg02_YAML重复ProviderId在绑定前失败() {
        assertDuplicateYamlKeyRejected("providers");
    }

    @Test
    void cfg03_YAML重复DeploymentId在绑定前失败() {
        assertDuplicateYamlKeyRejected("deployments");
    }

    @Test
    void cfg04_deployment引用不存在Provider时失败() {
        var properties = validProperties();
        properties.getDeployments().get("chat-a").setProviderId("missing-provider");

        assertInvalid(properties, "app.ai.deployments.chat-a.provider-id");
    }

    @Test
    void cfg05_Embedding缺维度或批量上限时失败() {
        var missingDimensions = validProperties();
        missingDimensions.getDeployments().get("embedding-a").getEmbedding().setDimensions(0);
        assertInvalid(missingDimensions, "dimensions");

        var missingBatchLimit = validProperties();
        missingBatchLimit.getDeployments().get("embedding-a").getEmbedding().setMaxBatchItems(0);
        assertInvalid(missingBatchLimit, "max-batch-items");
    }

    @Test
    void cfg06_同EmbeddingIdentity维度不一致时失败() {
        var properties = equivalentEmbeddingProperties();
        var second = embeddingDeployment("provider-a", "embed-v1", false, "artifact-v1", "RAW_UTF8_V1");
        second.getEmbedding().setDimensions(768);
        properties.getDeployments().put("embedding-b", second);

        assertInvalid(properties, "dimensions");
    }

    @Test
    void cfg07_同EmbeddingIdentity预处理指纹不一致时失败() {
        var properties = equivalentEmbeddingProperties();
        properties.getDeployments().put("embedding-b",
                embeddingDeployment("provider-a", "embed-v1", false, "artifact-v1", "NORMALIZED_V2"));

        assertInvalid(properties, "preprocessing-fingerprint");
    }

    @Test
    void cfg08_同能力和等级有两个默认候选时失败() {
        var properties = validProperties();
        properties.getDeployments().put("chat-b", chatDeployment("provider-a", true, 20));

        assertInvalid(properties, "default-candidate");
    }

    @Test
    void cfg09_缺密钥保留Provider与Deployment并标记未配置() {
        var missing = ModelCatalogFactory.create(validProperties(), name -> null);
        var configured = ModelCatalogFactory.create(validProperties(), name -> "present");

        assertThat(missing.providers().get(new ProviderId("provider-a")).configurationStatus())
                .isEqualTo(ProviderConfigurationStatus.UNCONFIGURED);
        assertThat(missing.deployments()).hasSize(2);
        assertThat(missing.configFingerprint()).isNotEqualTo(configured.configFingerprint());
    }

    @Test
    void cfg11_只轮换密钥值不改变指纹且快照不携带密钥() {
        var first = ModelCatalogFactory.create(validProperties(), name -> "secret-one");
        var second = ModelCatalogFactory.create(validProperties(), name -> "secret-two");

        assertThat(first.configFingerprint()).isEqualTo(second.configFingerprint());
        assertThat(first.toString()).doesNotContain("secret-one", "secret-two");
        assertThat(first.providers().values())
                .allSatisfy(provider -> assertThat(provider.getClass().getRecordComponents())
                        .extracting(component -> component.getName())
                        .doesNotContain("secret", "secretValue", "apiKey"));
    }

    @Test
    void cfg12_候选优先级变化会改变指纹() {
        var firstProperties = validProperties();
        var secondProperties = validProperties();
        secondProperties.getDeployments().get("chat-a").setPriority(11);

        var first = ModelCatalogFactory.create(firstProperties, name -> "present");
        var second = ModelCatalogFactory.create(secondProperties, name -> "present");

        assertThat(first.configFingerprint()).isNotEqualTo(second.configFingerprint());
    }

    @Test
    void cfg13_Noop不能登记为真实Deployment() {
        var properties = validProperties();
        properties.getDeployments().get("chat-a").setUpstreamModel("noop");

        assertInvalid(properties, "Noop");
    }

    @Test
    void cfg14_非正超时AttemptWindow或Buffer参数均阻止启动() {
        var timeout = validProperties();
        timeout.getRuntime().getChat().setTotalTimeout(Duration.ZERO);
        assertInvalid(timeout, "total-timeout");

        var attempts = validProperties();
        attempts.getRuntime().getChat().setMaxAttemptsPerWorkUnit(0);
        assertInvalid(attempts, "max-attempts-per-work-unit");

        var window = validProperties();
        window.getRuntime().getBreaker().setSlidingWindowSize(0);
        assertInvalid(window, "sliding-window-size");

        var buffer = validProperties();
        buffer.getRuntime().getStream().setProbeMaxBytes(0);
        assertInvalid(buffer, "probe-max-bytes");
    }

    @Test
    void cfg15_同EmbeddingIdentity制品版本不一致时失败() {
        var properties = equivalentEmbeddingProperties();
        properties.getDeployments().put("embedding-b",
                embeddingDeployment("provider-a", "embed-v1", false, "artifact-v2", "RAW_UTF8_V1"));

        assertInvalid(properties, "artifact-version");
    }

    @Test
    void cfg16_legacyIdentity不能绑定多个Deployment() {
        var properties = validProperties();
        properties.getDeployments().put("embedding-b",
                embeddingDeployment("provider-a", "embed-v1", true, null, "RAW_UTF8_V1"));

        assertInvalid(properties, "legacy-single-deployment");
    }

    @Test
    void cfg17_非LegacyEmbedding必须同时有制品版本和预处理指纹() {
        var missingArtifact = validProperties();
        var embedding = missingArtifact.getDeployments().get("embedding-a").getEmbedding();
        embedding.setLegacySingleDeployment(false);
        embedding.setArtifactVersion(null);
        assertInvalid(missingArtifact, "artifact-version");

        var missingPreprocessing = validProperties();
        var otherEmbedding = missingPreprocessing.getDeployments().get("embedding-a").getEmbedding();
        otherEmbedding.setLegacySingleDeployment(false);
        otherEmbedding.setArtifactVersion("artifact-v1");
        otherEmbedding.setPreprocessingFingerprint(" ");
        assertInvalid(missingPreprocessing, "preprocessing-fingerprint");
    }

    @Test
    void cfg18_Chat采样等级必须与Deployment等级完全一致() {
        var missing = validProperties();
        missing.getDeployments().get("chat-a").setChat(null);
        assertInvalid(missing, "chat");

        var mismatch = validProperties();
        mismatch.getDeployments().get("chat-a").getChat().getSamplingByTier()
                .put(ServiceTier.QUALITY, sampling(0.2, 0.5));
        assertInvalid(mismatch, "keys must exactly match");

        var nonChat = validProperties();
        nonChat.getDeployments().get("embedding-a").setChat(chatProfile());
        assertInvalid(nonChat, "only valid for Chat deployments");
    }

    @Test
    void cfg19_Chat采样数值缺失或越界时失败() {
        var missingTemperature = validProperties();
        missingTemperature.getDeployments().get("chat-a").getChat()
                .getSamplingByTier().get(ServiceTier.BALANCED).setTemperature(null);
        assertInvalid(missingTemperature, "temperature");

        var invalidTemperature = validProperties();
        invalidTemperature.getDeployments().get("chat-a").getChat()
                .getSamplingByTier().get(ServiceTier.BALANCED).setTemperature(2.1);
        assertInvalid(invalidTemperature, "temperature");

        var invalidTopP = validProperties();
        invalidTopP.getDeployments().get("chat-a").getChat()
                .getSamplingByTier().get(ServiceTier.BALANCED).setTopP(0.0);
        assertInvalid(invalidTopP, "top-p");
    }

    @Test
    void cfg20_Chat采样值进入稳定Fingerprint() {
        var firstProperties = validProperties();
        var secondProperties = validProperties();
        secondProperties.getDeployments().get("chat-a").getChat()
                .getSamplingByTier().get(ServiceTier.BALANCED).setTopP(0.4);

        var first = ModelCatalogFactory.create(firstProperties, name -> "present");
        var second = ModelCatalogFactory.create(secondProperties, name -> "present");

        assertThat(first.configFingerprint()).isNotEqualTo(second.configFingerprint());
        assertThatThrownBy(() -> first.deployments().get(new DeploymentId("chat-a"))
                        .chat().samplingByTier().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void cfg21_不支持采样映射的ChatAdapter在启动时失败() {
        var properties = validProperties();
        properties.getProviders().get("provider-a").setAdapter("unknown-chat-adapter");

        assertInvalid(properties, "does not support Chat sampling");
    }

    @Test
    void valueTypes在运行时仍保持不可混用身份() {
        assertThat(new ProviderId("same")).isNotEqualTo(new ModelIdentity("same"));
        assertThatThrownBy(() -> new DeploymentId("bad id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deploymentId");
    }

    private static AiModelsProperties validProperties() {
        var properties = new AiModelsProperties();
        properties.setPolicyVersion("test-v1");

        var provider = new AiModelsProperties.ProviderProperties();
        provider.setAdapter("openai-compatible");
        provider.setDisplayName("测试 Provider");
        provider.setBaseUrl("https://example.test/v1");
        provider.setSecretProperty("AI_SECRET");
        provider.setDataRegion("CN_TEST");
        provider.setQuotaDomain("quota-a");
        properties.setProviders(new LinkedHashMap<>(Map.of("provider-a", provider)));

        var deployments = new LinkedHashMap<String, AiModelsProperties.DeploymentProperties>();
        deployments.put("chat-a", chatDeployment("provider-a", true, 10));
        deployments.put("embedding-a",
                embeddingDeployment("provider-a", "embed-v1", true, null, "RAW_UTF8_V1"));
        properties.setDeployments(deployments);
        return properties;
    }

    private static AiModelsProperties equivalentEmbeddingProperties() {
        var properties = validProperties();
        var embedding = properties.getDeployments().get("embedding-a").getEmbedding();
        embedding.setLegacySingleDeployment(false);
        embedding.setArtifactVersion("artifact-v1");
        return properties;
    }

    private static AiModelsProperties.DeploymentProperties chatDeployment(
            String providerId, boolean defaultCandidate, int priority) {
        var deployment = new AiModelsProperties.DeploymentProperties();
        deployment.setProviderId(providerId);
        deployment.setUpstreamModel("chat-model");
        deployment.setModelIdentity("chat-model-v1");
        deployment.setCapabilities(Set.of(Capability.CHAT));
        deployment.setTiers(Set.of(ServiceTier.BALANCED));
        deployment.setDefaultCandidate(defaultCandidate);
        deployment.setPriority(priority);
        deployment.setEnabled(true);
        deployment.setChat(chatProfile());
        return deployment;
    }

    private static AiModelsProperties.ChatProperties chatProfile() {
        var chat = new AiModelsProperties.ChatProperties();
        chat.setSamplingByTier(new LinkedHashMap<>(Map.of(
                ServiceTier.BALANCED, sampling(0.1, 0.3))));
        return chat;
    }

    private static AiModelsProperties.ChatSamplingProperties sampling(
            double temperature, double topP) {
        var sampling = new AiModelsProperties.ChatSamplingProperties();
        sampling.setTemperature(temperature);
        sampling.setTopP(topP);
        return sampling;
    }

    private static AiModelsProperties.DeploymentProperties embeddingDeployment(
            String providerId,
            String identity,
            boolean legacy,
            String artifactVersion,
            String preprocessingFingerprint) {
        var deployment = new AiModelsProperties.DeploymentProperties();
        deployment.setProviderId(providerId);
        deployment.setUpstreamModel(identity);
        deployment.setModelIdentity(identity);
        deployment.setCapabilities(Set.of(Capability.EMBEDDING));
        deployment.setTiers(Set.of());
        deployment.setPriority(10);
        deployment.setEnabled(true);

        var embedding = new AiModelsProperties.EmbeddingProperties();
        embedding.setLegacySingleDeployment(legacy);
        embedding.setArtifactVersion(artifactVersion);
        embedding.setDimensions(1024);
        embedding.setMaxBatchItems(10);
        embedding.setMaxInputTokensPerItem(8192);
        embedding.setPreprocessingFingerprint(preprocessingFingerprint);
        deployment.setEmbedding(embedding);
        return deployment;
    }

    private static void assertInvalid(AiModelsProperties properties, String messagePart) {
        assertThatThrownBy(() -> ModelCatalogFactory.create(properties, name -> "present"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(messagePart);
    }

    private static void assertDuplicateYamlKeyRejected(String section) {
        String yaml = """
                app:
                  ai:
                    %s:
                      duplicate:
                        display-name: first
                      duplicate:
                        display-name: second
                """.formatted(section);
        var factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));

        assertThatThrownBy(factory::getObject)
                .hasMessageContaining("duplicate");
    }
}
