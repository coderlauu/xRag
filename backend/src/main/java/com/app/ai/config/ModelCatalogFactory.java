package com.app.ai.config;

import com.app.ai.model.AiRuntimePolicy;
import com.app.ai.model.Capability;
import com.app.ai.model.ChatProfile;
import com.app.ai.model.ChatSamplingParameters;
import com.app.ai.model.DataRegion;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.EmbeddingProfile;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ModelFeature;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderConfigurationStatus;
import com.app.ai.model.ProviderId;
import com.app.ai.model.RerankProfile;
import com.app.ai.model.QuotaDomain;
import com.app.ai.model.ServiceTier;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

final class ModelCatalogFactory {

    private ModelCatalogFactory() {
    }

    static ModelCatalogSnapshot create(
            AiModelsProperties properties, Function<String, String> secretResolver) {
        if (properties == null) {
            throw invalid("app.ai", "configuration is required");
        }
        if (secretResolver == null) {
            throw invalid("app.ai", "secret resolver is required");
        }

        String policyVersion = requireText("app.ai.policy-version", properties.getPolicyVersion());
        AiRuntimePolicy runtimePolicy = runtimePolicy(properties.getRuntime());
        Map<ProviderId, ModelProvider> providers = providers(properties.getProviders(), secretResolver);
        Map<DeploymentId, ModelDeployment> deployments = deployments(properties.getDeployments(), providers);
        validateDefaultCandidates(deployments.values());
        validateEmbeddingIdentities(deployments.values());

        String fingerprint = fingerprint(policyVersion, providers, deployments, runtimePolicy);
        return new ModelCatalogSnapshot(policyVersion, fingerprint, providers, deployments, runtimePolicy);
    }

    private static Map<ProviderId, ModelProvider> providers(
            Map<String, AiModelsProperties.ProviderProperties> configured,
            Function<String, String> secretResolver) {
        Map<ProviderId, ModelProvider> result = new TreeMap<>();
        for (Map.Entry<String, AiModelsProperties.ProviderProperties> entry
                : orderedEntries(configured, "app.ai.providers")) {
            String path = "app.ai.providers." + entry.getKey();
            ProviderId id = providerId(path, entry.getKey());
            AiModelsProperties.ProviderProperties source = entry.getValue();
            if (source == null) {
                throw invalid(path, "provider configuration is required");
            }

            String adapter = requireText(path + ".adapter", source.getAdapter());
            String displayName = requireText(path + ".display-name", source.getDisplayName());
            URI baseUrl = baseUrl(path + ".base-url", source.getBaseUrl());
            String secretProperty = requireText(path + ".secret-property", source.getSecretProperty());
            DataRegion dataRegion = dataRegion(path + ".data-region", source.getDataRegion());
            QuotaDomain quotaDomain = quotaDomain(path + ".quota-domain", source.getQuotaDomain());
            String secret = secretResolver.apply(secretProperty);
            ProviderConfigurationStatus status = secret == null || secret.isBlank()
                    ? ProviderConfigurationStatus.UNCONFIGURED
                    : ProviderConfigurationStatus.CONFIGURED;

            result.put(id, new ModelProvider(
                    id,
                    adapter,
                    displayName,
                    baseUrl,
                    secretProperty,
                    dataRegion,
                    quotaDomain,
                    status));
        }
        return immutableOrdered(result);
    }

    private static Map<DeploymentId, ModelDeployment> deployments(
            Map<String, AiModelsProperties.DeploymentProperties> configured,
            Map<ProviderId, ModelProvider> providers) {
        Map<DeploymentId, ModelDeployment> result = new TreeMap<>();
        for (Map.Entry<String, AiModelsProperties.DeploymentProperties> entry
                : orderedEntries(configured, "app.ai.deployments")) {
            String path = "app.ai.deployments." + entry.getKey();
            DeploymentId id = deploymentId(path, entry.getKey());
            AiModelsProperties.DeploymentProperties source = entry.getValue();
            if (source == null) {
                throw invalid(path, "deployment configuration is required");
            }

            ProviderId providerId = providerId(path + ".provider-id", source.getProviderId());
            if (!providers.containsKey(providerId)) {
                throw invalid(path + ".provider-id", "references unknown provider " + providerId);
            }
            String upstreamModel = requireText(path + ".upstream-model", source.getUpstreamModel());
            ModelIdentity modelIdentity = modelIdentity(path + ".model-identity", source.getModelIdentity());
            if (isNoop(entry.getKey()) || isNoop(upstreamModel) || isNoop(modelIdentity.value())) {
                throw invalid(path, "Noop is not a real deployment");
            }

            Set<Capability> capabilities = capabilities(path + ".capabilities", source.getCapabilities());
            Set<ServiceTier> tiers = tiers(path + ".tiers", source.getTiers());
            Set<ModelFeature> features = features(path + ".features", source.getFeatures());
            boolean gradedCapability = capabilities.stream().anyMatch(capability -> capability != Capability.EMBEDDING);
            if (gradedCapability && tiers.isEmpty()) {
                throw invalid(path + ".tiers", "at least one tier is required for Chat, stream Chat, or Rerank");
            }
            if (!gradedCapability && !tiers.isEmpty()) {
                throw invalid(path + ".tiers", "Embedding-only deployments must not declare service tiers");
            }
            if (source.getPriority() < 0) {
                throw invalid(path + ".priority", "must be non-negative");
            }
            if (source.isDefaultCandidate() && tiers.isEmpty()) {
                throw invalid(path + ".default-candidate", "requires at least one service tier");
            }

            EmbeddingProfile embedding = embeddingProfile(path, capabilities, source.getEmbedding());
            RerankProfile rerank = rerankProfile(path, capabilities, source.getRerank());
            ChatProfile chat = chatProfile(
                    path,
                    capabilities,
                    tiers,
                    source.getChat(),
                    providers.get(providerId).adapter());
            result.put(id, new ModelDeployment(
                    id,
                    providerId,
                    upstreamModel,
                    modelIdentity,
                    capabilities,
                    tiers,
                    source.isDefaultCandidate(),
                    source.getPriority(),
                    source.isEnabled(),
                    embedding,
                    features,
                    rerank,
                    chat));
        }
        return immutableOrdered(result);
    }

    private static ChatProfile chatProfile(
            String deploymentPath,
            Set<Capability> capabilities,
            Set<ServiceTier> tiers,
            AiModelsProperties.ChatProperties source,
            String providerAdapter) {
        boolean supportsChat = capabilities.contains(Capability.CHAT)
                || capabilities.contains(Capability.STREAM_CHAT);
        if (!supportsChat) {
            if (source != null) {
                throw invalid(deploymentPath + ".chat", "is only valid for Chat deployments");
            }
            return null;
        }
        if (source == null) {
            throw invalid(deploymentPath + ".chat", "is required for Chat deployments");
        }
        if (!"openai-compatible".equals(providerAdapter)) {
            throw invalid(deploymentPath + ".chat", "provider adapter does not support Chat sampling");
        }
        Map<ServiceTier, AiModelsProperties.ChatSamplingProperties> configured =
                source.getSamplingByTier();
        String path = deploymentPath + ".chat.sampling-by-tier";
        if (configured == null || configured.isEmpty()) {
            throw invalid(path, "must not be empty");
        }
        if (!configured.keySet().equals(tiers)) {
            throw invalid(path, "keys must exactly match deployment tiers");
        }
        Map<ServiceTier, ChatSamplingParameters> sampling = new java.util.EnumMap<>(ServiceTier.class);
        for (ServiceTier tier : ServiceTier.values()) {
            if (!configured.containsKey(tier)) {
                continue;
            }
            String tierPath = path + "." + tier;
            AiModelsProperties.ChatSamplingProperties values = configured.get(tier);
            if (values == null) {
                throw invalid(tierPath, "configuration is required");
            }
            Double temperature = values.getTemperature();
            if (temperature == null) {
                throw invalid(tierPath + ".temperature", "is required");
            }
            if (!Double.isFinite(temperature) || temperature < 0 || temperature > 2) {
                throw invalid(tierPath + ".temperature", "must be finite and between 0 and 2");
            }
            Double topP = values.getTopP();
            if (topP == null) {
                throw invalid(tierPath + ".top-p", "is required");
            }
            if (!Double.isFinite(topP) || topP <= 0 || topP > 1) {
                throw invalid(tierPath + ".top-p", "must be finite and greater than 0 and at most 1");
            }
            sampling.put(tier, new ChatSamplingParameters(temperature, topP));
        }
        return new ChatProfile(sampling);
    }

    private static RerankProfile rerankProfile(
            String deploymentPath,
            Set<Capability> capabilities,
            AiModelsProperties.RerankProperties source) {
        if (!capabilities.contains(Capability.RERANK)) {
            if (source != null) {
                throw invalid(deploymentPath + ".rerank", "is only valid for RERANK deployments");
            }
            return null;
        }
        if (source == null) {
            throw invalid(deploymentPath + ".rerank", "is required for RERANK deployments");
        }
        if (source.getMaxDocuments() <= 0) {
            throw invalid(deploymentPath + ".rerank.max-documents", "must be positive");
        }
        return new RerankProfile(source.getMaxDocuments());
    }

    private static EmbeddingProfile embeddingProfile(
            String deploymentPath,
            Set<Capability> capabilities,
            AiModelsProperties.EmbeddingProperties source) {
        boolean supportsEmbedding = capabilities.contains(Capability.EMBEDDING);
        if (!supportsEmbedding) {
            if (source != null) {
                throw invalid(deploymentPath + ".embedding", "is only valid for EMBEDDING deployments");
            }
            return null;
        }
        if (source == null) {
            throw invalid(deploymentPath + ".embedding", "is required for EMBEDDING deployments");
        }
        if (source.getDimensions() <= 0) {
            throw invalid(deploymentPath + ".embedding.dimensions", "must be positive");
        }
        if (source.getMaxBatchItems() <= 0) {
            throw invalid(deploymentPath + ".embedding.max-batch-items", "must be positive");
        }
        if (source.getMaxInputTokensPerItem() <= 0) {
            throw invalid(deploymentPath + ".embedding.max-input-tokens-per-item", "must be positive");
        }

        String artifactVersion = trimToNull(source.getArtifactVersion());
        String preprocessing = trimToNull(source.getPreprocessingFingerprint());
        if (!source.isLegacySingleDeployment()) {
            if (artifactVersion == null) {
                throw invalid(deploymentPath + ".embedding.artifact-version",
                        "is required unless legacy-single-deployment is true");
            }
            if (preprocessing == null) {
                throw invalid(deploymentPath + ".embedding.preprocessing-fingerprint",
                        "is required unless legacy-single-deployment is true");
            }
        }
        return new EmbeddingProfile(
                source.isLegacySingleDeployment(),
                artifactVersion,
                source.getDimensions(),
                source.getMaxBatchItems(),
                source.getMaxInputTokensPerItem(),
                preprocessing);
    }

    private static void validateDefaultCandidates(Collection<ModelDeployment> deployments) {
        Set<String> defaults = new HashSet<>();
        for (ModelDeployment deployment : deployments) {
            if (!deployment.defaultCandidate()) {
                continue;
            }
            for (Capability capability : deployment.capabilities()) {
                if (capability == Capability.EMBEDDING) {
                    continue;
                }
                for (ServiceTier tier : deployment.tiers()) {
                    String key = capability + "/" + tier;
                    if (!defaults.add(key)) {
                        throw invalid("app.ai.deployments.*.default-candidate",
                                "more than one default-candidate for " + key);
                    }
                }
            }
        }
    }

    private static void validateEmbeddingIdentities(Collection<ModelDeployment> deployments) {
        Map<ModelIdentity, List<ModelDeployment>> byIdentity = new HashMap<>();
        for (ModelDeployment deployment : deployments) {
            if (deployment.embedding() != null) {
                byIdentity.computeIfAbsent(deployment.modelIdentity(), ignored -> new ArrayList<>())
                        .add(deployment);
            }
        }

        for (Map.Entry<ModelIdentity, List<ModelDeployment>> entry : byIdentity.entrySet()) {
            List<ModelDeployment> equivalents = entry.getValue();
            if (equivalents.size() < 2) {
                continue;
            }
            if (equivalents.stream().anyMatch(deployment -> deployment.embedding().legacySingleDeployment())) {
                throw invalid("app.ai.deployments.*.embedding.legacy-single-deployment",
                        "ModelIdentity " + entry.getKey() + " may bind only one deployment");
            }

            EmbeddingProfile expected = equivalents.get(0).embedding();
            for (ModelDeployment deployment : equivalents.subList(1, equivalents.size())) {
                EmbeddingProfile actual = deployment.embedding();
                if (actual.dimensions() != expected.dimensions()) {
                    throw invalid("app.ai.deployments.*.embedding.dimensions",
                            "equivalent deployments for " + entry.getKey() + " must match");
                }
                if (!actual.preprocessingFingerprint().equals(expected.preprocessingFingerprint())) {
                    throw invalid("app.ai.deployments.*.embedding.preprocessing-fingerprint",
                            "equivalent deployments for " + entry.getKey() + " must match");
                }
                if (!actual.artifactVersion().equals(expected.artifactVersion())) {
                    throw invalid("app.ai.deployments.*.embedding.artifact-version",
                            "equivalent deployments for " + entry.getKey() + " must match");
                }
            }
        }
    }

    private static AiRuntimePolicy runtimePolicy(AiModelsProperties.RuntimeProperties source) {
        if (source == null) {
            throw invalid("app.ai.runtime", "configuration is required");
        }
        AiRuntimePolicy.CapabilityLimits chat = capabilityLimits("chat", source.getChat());
        AiRuntimePolicy.CapabilityLimits streamChat = capabilityLimits("stream-chat", source.getStreamChat());
        AiRuntimePolicy.CapabilityLimits embedding = capabilityLimits("embedding", source.getEmbedding());
        AiRuntimePolicy.CapabilityLimits rerank = capabilityLimits("rerank", source.getRerank());

        AiModelsProperties.StreamProperties stream = source.getStream();
        if (stream == null) {
            throw invalid("app.ai.runtime.stream", "configuration is required");
        }
        positive("app.ai.runtime.stream.probe-max-events", stream.getProbeMaxEvents());
        positive("app.ai.runtime.stream.probe-max-bytes", stream.getProbeMaxBytes());
        positive("app.ai.runtime.stream.sse-event-max-bytes", stream.getSseEventMaxBytes());
        AiRuntimePolicy.StreamLimits streamLimits = new AiRuntimePolicy.StreamLimits(
                stream.getProbeMaxEvents(), stream.getProbeMaxBytes(), stream.getSseEventMaxBytes());

        AiModelsProperties.BreakerProperties breaker = source.getBreaker();
        if (breaker == null) {
            throw invalid("app.ai.runtime.breaker", "configuration is required");
        }
        positive("app.ai.runtime.breaker.sliding-window-size", breaker.getSlidingWindowSize());
        positive("app.ai.runtime.breaker.minimum-number-of-calls", breaker.getMinimumNumberOfCalls());
        if (breaker.getMinimumNumberOfCalls() > breaker.getSlidingWindowSize()) {
            throw invalid("app.ai.runtime.breaker.minimum-number-of-calls",
                    "must not exceed sliding-window-size");
        }
        percentage("app.ai.runtime.breaker.failure-rate-threshold", breaker.getFailureRateThreshold());
        percentage("app.ai.runtime.breaker.slow-call-rate-threshold", breaker.getSlowCallRateThreshold());
        positiveDuration("app.ai.runtime.breaker.open-wait-duration", breaker.getOpenWaitDuration());
        positive("app.ai.runtime.breaker.half-open-permits", breaker.getHalfOpenPermits());
        positiveDuration("app.ai.runtime.breaker.half-open-max-wait", breaker.getHalfOpenMaxWait());
        AiRuntimePolicy.BreakerLimits breakerLimits = new AiRuntimePolicy.BreakerLimits(
                breaker.getSlidingWindowSize(),
                breaker.getMinimumNumberOfCalls(),
                breaker.getFailureRateThreshold(),
                breaker.getSlowCallRateThreshold(),
                breaker.getOpenWaitDuration(),
                breaker.getHalfOpenPermits(),
                breaker.getHalfOpenMaxWait());

        return new AiRuntimePolicy(chat, streamChat, embedding, rerank, streamLimits, breakerLimits);
    }

    private static AiRuntimePolicy.CapabilityLimits capabilityLimits(
            String name, AiModelsProperties.CapabilityLimitProperties source) {
        String path = "app.ai.runtime." + name;
        if (source == null) {
            throw invalid(path, "configuration is required");
        }
        positiveDuration(path + ".total-timeout", source.getTotalTimeout());
        positive(path + ".max-attempts-per-work-unit", source.getMaxAttemptsPerWorkUnit());
        positive(path + ".max-same-deployment-attempts", source.getMaxSameDeploymentAttempts());
        if (source.getMaxSameDeploymentAttempts() > source.getMaxAttemptsPerWorkUnit()) {
            throw invalid(path + ".max-same-deployment-attempts",
                    "must not exceed max-attempts-per-work-unit");
        }
        return new AiRuntimePolicy.CapabilityLimits(
                source.getTotalTimeout(),
                source.getMaxAttemptsPerWorkUnit(),
                source.getMaxSameDeploymentAttempts());
    }

    private static String fingerprint(
            String policyVersion,
            Map<ProviderId, ModelProvider> providers,
            Map<DeploymentId, ModelDeployment> deployments,
            AiRuntimePolicy runtimePolicy) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, policyVersion);
        for (ModelProvider provider : providers.values()) {
            append(canonical,
                    provider.id().value(),
                    provider.adapter(),
                    provider.displayName(),
                    provider.baseUrl().toString(),
                    provider.secretProperty(),
                    provider.dataRegion().value(),
                    provider.quotaDomain().value(),
                    provider.configurationStatus().name());
        }
        for (ModelDeployment deployment : deployments.values()) {
            append(canonical,
                    deployment.id().value(),
                    deployment.providerId().value(),
                    deployment.upstreamModel(),
                    deployment.modelIdentity().value(),
                    sortedNames(deployment.capabilities()),
                    sortedNames(deployment.tiers()),
                    Boolean.toString(deployment.defaultCandidate()),
                    Integer.toString(deployment.priority()),
                    Boolean.toString(deployment.enabled()),
                    sortedNames(deployment.features()));
            EmbeddingProfile embedding = deployment.embedding();
            if (embedding != null) {
                append(canonical,
                        Boolean.toString(embedding.legacySingleDeployment()),
                        embedding.artifactVersion(),
                        Integer.toString(embedding.dimensions()),
                        Integer.toString(embedding.maxBatchItems()),
                        Integer.toString(embedding.maxInputTokensPerItem()),
                        embedding.preprocessingFingerprint());
            }
            ChatProfile chat = deployment.chat();
            if (chat != null) {
                for (ServiceTier tier : ServiceTier.values()) {
                    ChatSamplingParameters sampling = chat.samplingByTier().get(tier);
                    if (sampling != null) {
                        append(canonical,
                                tier.name(),
                                Double.toString(sampling.temperature()),
                                Double.toString(sampling.topP()));
                    }
                }
            }
        }
        append(canonical,
                runtimePolicy.chat().toString(),
                runtimePolicy.streamChat().toString(),
                runtimePolicy.embedding().toString(),
                runtimePolicy.rerank().toString(),
                runtimePolicy.stream().toString(),
                runtimePolicy.breaker().toString());
        return sha256(canonical.toString());
    }

    private static void append(StringBuilder target, String... values) {
        for (String value : values) {
            String normalized = value == null ? "<null>" : value;
            target.append(normalized.length()).append(':').append(normalized).append('|');
        }
    }

    private static String sortedNames(Collection<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().reduce((left, right) -> left + "," + right).orElse("");
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static <T> List<Map.Entry<String, T>> orderedEntries(Map<String, T> values, String path) {
        if (values == null) {
            throw invalid(path, "must not be null");
        }
        return values.entrySet().stream()
                .sorted(Map.Entry.comparingByKey(Comparator.nullsFirst(String::compareTo)))
                .toList();
    }

    private static <K, V> Map<K, V> immutableOrdered(Map<K, V> sorted) {
        return java.util.Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    private static Set<Capability> capabilities(String path, Set<Capability> values) {
        if (values == null || values.isEmpty() || values.stream().anyMatch(Objects::isNull)) {
            throw invalid(path, "must contain at least one capability");
        }
        return Set.copyOf(EnumSet.copyOf(values));
    }

    private static Set<ServiceTier> tiers(String path, Set<ServiceTier> values) {
        if (values == null) {
            throw invalid(path, "must not be null");
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw invalid(path, "must not contain null");
        }
        return values.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(values));
    }

    private static Set<ModelFeature> features(String path, Set<ModelFeature> values) {
        if (values == null) {
            throw invalid(path, "must not be null");
        }
        if (values.stream().anyMatch(Objects::isNull)) {
            throw invalid(path, "must not contain null");
        }
        return values.isEmpty() ? Set.of() : Set.copyOf(EnumSet.copyOf(values));
    }

    private static ProviderId providerId(String path, String value) {
        try {
            return new ProviderId(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(path, exception.getMessage());
        }
    }

    private static DeploymentId deploymentId(String path, String value) {
        try {
            return new DeploymentId(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(path, exception.getMessage());
        }
    }

    private static ModelIdentity modelIdentity(String path, String value) {
        try {
            return new ModelIdentity(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(path, exception.getMessage());
        }
    }

    private static DataRegion dataRegion(String path, String value) {
        try {
            return new DataRegion(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(path, exception.getMessage());
        }
    }

    private static QuotaDomain quotaDomain(String path, String value) {
        try {
            return new QuotaDomain(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(path, exception.getMessage());
        }
    }

    private static URI baseUrl(String path, String value) {
        String required = requireText(path, value);
        try {
            URI uri = new URI(required);
            if (!uri.isAbsolute() || !("http".equalsIgnoreCase(uri.getScheme())
                    || "https".equalsIgnoreCase(uri.getScheme()))) {
                throw invalid(path, "must be an absolute HTTP(S) URL");
            }
            return uri;
        } catch (URISyntaxException exception) {
            throw invalid(path, "must be a valid URI");
        }
    }

    private static String requireText(String path, String value) {
        String normalized = trimToNull(value);
        if (normalized == null) {
            throw invalid(path, "must not be blank");
        }
        return normalized;
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static boolean isNoop(String value) {
        return value != null && "noop".equalsIgnoreCase(value.trim());
    }

    private static void positive(String path, int value) {
        if (value <= 0) {
            throw invalid(path, "must be positive");
        }
    }

    private static void positiveDuration(String path, Duration value) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw invalid(path, "must be a positive duration");
        }
    }

    private static void percentage(String path, double value) {
        if (!Double.isFinite(value) || value <= 0 || value > 100) {
            throw invalid(path, "must be in (0, 100]");
        }
    }

    private static IllegalStateException invalid(String path, String detail) {
        return new IllegalStateException(path + ": " + detail);
    }
}
