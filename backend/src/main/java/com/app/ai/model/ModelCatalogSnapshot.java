package com.app.ai.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record ModelCatalogSnapshot(
        String policyVersion,
        String configFingerprint,
        Map<ProviderId, ModelProvider> providers,
        Map<DeploymentId, ModelDeployment> deployments,
        AiRuntimePolicy runtimePolicy) {

    public ModelCatalogSnapshot {
        Objects.requireNonNull(policyVersion);
        Objects.requireNonNull(configFingerprint);
        providers = immutableOrderedCopy(providers);
        deployments = immutableOrderedCopy(deployments);
        Objects.requireNonNull(runtimePolicy);
    }

    private static <K, V> Map<K, V> immutableOrderedCopy(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }
}
