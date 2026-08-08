package com.app.ai.model;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public record ChatProfile(Map<ServiceTier, ChatSamplingParameters> samplingByTier) {

    public ChatProfile {
        Objects.requireNonNull(samplingByTier);
        if (samplingByTier.isEmpty()) {
            throw new IllegalArgumentException("samplingByTier must not be empty");
        }
        var copy = new EnumMap<ServiceTier, ChatSamplingParameters>(ServiceTier.class);
        samplingByTier.forEach((tier, parameters) -> copy.put(
                Objects.requireNonNull(tier), Objects.requireNonNull(parameters)));
        samplingByTier = Collections.unmodifiableMap(copy);
    }

    public ChatSamplingParameters sampling(ServiceTier tier) {
        ChatSamplingParameters parameters = samplingByTier.get(Objects.requireNonNull(tier));
        if (parameters == null) {
            throw new IllegalArgumentException("no Chat sampling parameters for tier " + tier);
        }
        return parameters;
    }
}
