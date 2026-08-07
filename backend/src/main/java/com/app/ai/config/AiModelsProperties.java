package com.app.ai.config;

import com.app.ai.model.Capability;
import com.app.ai.model.ModelFeature;
import com.app.ai.model.ServiceTier;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ai")
public class AiModelsProperties {

    private String policyVersion;
    private Map<String, ProviderProperties> providers = new LinkedHashMap<>();
    private Map<String, DeploymentProperties> deployments = new LinkedHashMap<>();
    private RuntimeProperties runtime = new RuntimeProperties();

    public String getPolicyVersion() {
        return policyVersion;
    }

    public void setPolicyVersion(String policyVersion) {
        this.policyVersion = policyVersion;
    }

    public Map<String, ProviderProperties> getProviders() {
        return providers;
    }

    public void setProviders(Map<String, ProviderProperties> providers) {
        this.providers = providers;
    }

    public Map<String, DeploymentProperties> getDeployments() {
        return deployments;
    }

    public void setDeployments(Map<String, DeploymentProperties> deployments) {
        this.deployments = deployments;
    }

    public RuntimeProperties getRuntime() {
        return runtime;
    }

    public void setRuntime(RuntimeProperties runtime) {
        this.runtime = runtime;
    }

    public static class ProviderProperties {

        private String adapter;
        private String displayName;
        private String baseUrl;
        private String secretProperty;
        private String dataRegion;
        private String quotaDomain;

        public String getAdapter() {
            return adapter;
        }

        public void setAdapter(String adapter) {
            this.adapter = adapter;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getSecretProperty() {
            return secretProperty;
        }

        public void setSecretProperty(String secretProperty) {
            this.secretProperty = secretProperty;
        }

        public String getDataRegion() {
            return dataRegion;
        }

        public void setDataRegion(String dataRegion) {
            this.dataRegion = dataRegion;
        }

        public String getQuotaDomain() {
            return quotaDomain;
        }

        public void setQuotaDomain(String quotaDomain) {
            this.quotaDomain = quotaDomain;
        }
    }

    public static class DeploymentProperties {

        private String providerId;
        private String upstreamModel;
        private String modelIdentity;
        private Set<Capability> capabilities = new LinkedHashSet<>();
        private Set<ServiceTier> tiers = new LinkedHashSet<>();
        private Set<ModelFeature> features = new LinkedHashSet<>();
        private boolean defaultCandidate;
        private int priority;
        private boolean enabled = true;
        private EmbeddingProperties embedding;
        private RerankProperties rerank;

        public String getProviderId() {
            return providerId;
        }

        public void setProviderId(String providerId) {
            this.providerId = providerId;
        }

        public String getUpstreamModel() {
            return upstreamModel;
        }

        public void setUpstreamModel(String upstreamModel) {
            this.upstreamModel = upstreamModel;
        }

        public String getModelIdentity() {
            return modelIdentity;
        }

        public void setModelIdentity(String modelIdentity) {
            this.modelIdentity = modelIdentity;
        }

        public Set<Capability> getCapabilities() {
            return capabilities;
        }

        public void setCapabilities(Set<Capability> capabilities) {
            this.capabilities = capabilities;
        }

        public Set<ServiceTier> getTiers() {
            return tiers;
        }

        public void setTiers(Set<ServiceTier> tiers) {
            this.tiers = tiers;
        }

        public Set<ModelFeature> getFeatures() {
            return features;
        }

        public void setFeatures(Set<ModelFeature> features) {
            this.features = features;
        }

        public boolean isDefaultCandidate() {
            return defaultCandidate;
        }

        public void setDefaultCandidate(boolean defaultCandidate) {
            this.defaultCandidate = defaultCandidate;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public EmbeddingProperties getEmbedding() {
            return embedding;
        }

        public void setEmbedding(EmbeddingProperties embedding) {
            this.embedding = embedding;
        }

        public RerankProperties getRerank() {
            return rerank;
        }

        public void setRerank(RerankProperties rerank) {
            this.rerank = rerank;
        }
    }

    /** Rerank deployment 的调用上限，只对声明了 RERANK 能力的 deployment 有效。 */
    public static class RerankProperties {

        private int maxDocuments;

        public int getMaxDocuments() {
            return maxDocuments;
        }

        public void setMaxDocuments(int maxDocuments) {
            this.maxDocuments = maxDocuments;
        }
    }

    public static class EmbeddingProperties {

        private boolean legacySingleDeployment;
        private String artifactVersion;
        private int dimensions;
        private int maxBatchItems;
        private int maxInputTokensPerItem;
        private String preprocessingFingerprint;

        public boolean isLegacySingleDeployment() {
            return legacySingleDeployment;
        }

        public void setLegacySingleDeployment(boolean legacySingleDeployment) {
            this.legacySingleDeployment = legacySingleDeployment;
        }

        public String getArtifactVersion() {
            return artifactVersion;
        }

        public void setArtifactVersion(String artifactVersion) {
            this.artifactVersion = artifactVersion;
        }

        public int getDimensions() {
            return dimensions;
        }

        public void setDimensions(int dimensions) {
            this.dimensions = dimensions;
        }

        public int getMaxBatchItems() {
            return maxBatchItems;
        }

        public void setMaxBatchItems(int maxBatchItems) {
            this.maxBatchItems = maxBatchItems;
        }

        public int getMaxInputTokensPerItem() {
            return maxInputTokensPerItem;
        }

        public void setMaxInputTokensPerItem(int maxInputTokensPerItem) {
            this.maxInputTokensPerItem = maxInputTokensPerItem;
        }

        public String getPreprocessingFingerprint() {
            return preprocessingFingerprint;
        }

        public void setPreprocessingFingerprint(String preprocessingFingerprint) {
            this.preprocessingFingerprint = preprocessingFingerprint;
        }
    }

    public static class RuntimeProperties {

        private CapabilityLimitProperties chat = new CapabilityLimitProperties(Duration.ofSeconds(45), 3, 2);
        private CapabilityLimitProperties streamChat =
                new CapabilityLimitProperties(Duration.ofSeconds(120), 3, 1);
        private CapabilityLimitProperties embedding =
                new CapabilityLimitProperties(Duration.ofMinutes(15), 3, 2);
        private CapabilityLimitProperties rerank = new CapabilityLimitProperties(Duration.ofSeconds(20), 3, 2);
        private StreamProperties stream = new StreamProperties();
        private BreakerProperties breaker = new BreakerProperties();

        public CapabilityLimitProperties getChat() {
            return chat;
        }

        public void setChat(CapabilityLimitProperties chat) {
            this.chat = chat;
        }

        public CapabilityLimitProperties getStreamChat() {
            return streamChat;
        }

        public void setStreamChat(CapabilityLimitProperties streamChat) {
            this.streamChat = streamChat;
        }

        public CapabilityLimitProperties getEmbedding() {
            return embedding;
        }

        public void setEmbedding(CapabilityLimitProperties embedding) {
            this.embedding = embedding;
        }

        public CapabilityLimitProperties getRerank() {
            return rerank;
        }

        public void setRerank(CapabilityLimitProperties rerank) {
            this.rerank = rerank;
        }

        public StreamProperties getStream() {
            return stream;
        }

        public void setStream(StreamProperties stream) {
            this.stream = stream;
        }

        public BreakerProperties getBreaker() {
            return breaker;
        }

        public void setBreaker(BreakerProperties breaker) {
            this.breaker = breaker;
        }
    }

    public static class CapabilityLimitProperties {

        private Duration totalTimeout;
        private int maxAttemptsPerWorkUnit;
        private int maxSameDeploymentAttempts;

        public CapabilityLimitProperties() {
        }

        CapabilityLimitProperties(
                Duration totalTimeout, int maxAttemptsPerWorkUnit, int maxSameDeploymentAttempts) {
            this.totalTimeout = totalTimeout;
            this.maxAttemptsPerWorkUnit = maxAttemptsPerWorkUnit;
            this.maxSameDeploymentAttempts = maxSameDeploymentAttempts;
        }

        public Duration getTotalTimeout() {
            return totalTimeout;
        }

        public void setTotalTimeout(Duration totalTimeout) {
            this.totalTimeout = totalTimeout;
        }

        public int getMaxAttemptsPerWorkUnit() {
            return maxAttemptsPerWorkUnit;
        }

        public void setMaxAttemptsPerWorkUnit(int maxAttemptsPerWorkUnit) {
            this.maxAttemptsPerWorkUnit = maxAttemptsPerWorkUnit;
        }

        public int getMaxSameDeploymentAttempts() {
            return maxSameDeploymentAttempts;
        }

        public void setMaxSameDeploymentAttempts(int maxSameDeploymentAttempts) {
            this.maxSameDeploymentAttempts = maxSameDeploymentAttempts;
        }
    }

    public static class StreamProperties {

        private int probeMaxEvents = 32;
        private int probeMaxBytes = 65_536;
        private int sseEventMaxBytes = 65_536;

        public int getProbeMaxEvents() {
            return probeMaxEvents;
        }

        public void setProbeMaxEvents(int probeMaxEvents) {
            this.probeMaxEvents = probeMaxEvents;
        }

        public int getProbeMaxBytes() {
            return probeMaxBytes;
        }

        public void setProbeMaxBytes(int probeMaxBytes) {
            this.probeMaxBytes = probeMaxBytes;
        }

        public int getSseEventMaxBytes() {
            return sseEventMaxBytes;
        }

        public void setSseEventMaxBytes(int sseEventMaxBytes) {
            this.sseEventMaxBytes = sseEventMaxBytes;
        }
    }

    public static class BreakerProperties {

        private int slidingWindowSize = 20;
        private int minimumNumberOfCalls = 5;
        private double failureRateThreshold = 50.0;
        private double slowCallRateThreshold = 50.0;
        private Duration openWaitDuration = Duration.ofSeconds(30);
        private int halfOpenPermits = 2;
        private Duration halfOpenMaxWait = Duration.ofSeconds(30);

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public double getFailureRateThreshold() {
            return failureRateThreshold;
        }

        public void setFailureRateThreshold(double failureRateThreshold) {
            this.failureRateThreshold = failureRateThreshold;
        }

        public double getSlowCallRateThreshold() {
            return slowCallRateThreshold;
        }

        public void setSlowCallRateThreshold(double slowCallRateThreshold) {
            this.slowCallRateThreshold = slowCallRateThreshold;
        }

        public Duration getOpenWaitDuration() {
            return openWaitDuration;
        }

        public void setOpenWaitDuration(Duration openWaitDuration) {
            this.openWaitDuration = openWaitDuration;
        }

        public int getHalfOpenPermits() {
            return halfOpenPermits;
        }

        public void setHalfOpenPermits(int halfOpenPermits) {
            this.halfOpenPermits = halfOpenPermits;
        }

        public Duration getHalfOpenMaxWait() {
            return halfOpenMaxWait;
        }

        public void setHalfOpenMaxWait(Duration halfOpenMaxWait) {
            this.halfOpenMaxWait = halfOpenMaxWait;
        }
    }
}
