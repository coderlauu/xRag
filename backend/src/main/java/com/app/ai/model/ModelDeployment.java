package com.app.ai.model;

import java.util.Objects;
import java.util.Set;

public record ModelDeployment(
        DeploymentId id,
        ProviderId providerId,
        String upstreamModel,
        ModelIdentity modelIdentity,
        Set<Capability> capabilities,
        Set<ServiceTier> tiers,
        boolean defaultCandidate,
        int priority,
        boolean enabled,
        EmbeddingProfile embedding,
        Set<ModelFeature> features) {

    public ModelDeployment(
            DeploymentId id,
            ProviderId providerId,
            String upstreamModel,
            ModelIdentity modelIdentity,
            Set<Capability> capabilities,
            Set<ServiceTier> tiers,
            boolean defaultCandidate,
            int priority,
            boolean enabled,
            EmbeddingProfile embedding) {
        this(
                id,
                providerId,
                upstreamModel,
                modelIdentity,
                capabilities,
                tiers,
                defaultCandidate,
                priority,
                enabled,
                embedding,
                Set.of());
    }

    public ModelDeployment {
        Objects.requireNonNull(id);
        Objects.requireNonNull(providerId);
        Objects.requireNonNull(upstreamModel);
        Objects.requireNonNull(modelIdentity);
        capabilities = Set.copyOf(capabilities);
        tiers = Set.copyOf(tiers);
        features = Set.copyOf(features);
    }
}
