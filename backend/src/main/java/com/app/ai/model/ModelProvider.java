package com.app.ai.model;

import java.net.URI;
import java.util.Objects;

public record ModelProvider(
        ProviderId id,
        String adapter,
        String displayName,
        URI baseUrl,
        String secretProperty,
        DataRegion dataRegion,
        QuotaDomain quotaDomain,
        ProviderConfigurationStatus configurationStatus) {

    public ModelProvider {
        Objects.requireNonNull(id);
        Objects.requireNonNull(adapter);
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(baseUrl);
        Objects.requireNonNull(secretProperty);
        Objects.requireNonNull(dataRegion);
        Objects.requireNonNull(quotaDomain);
        Objects.requireNonNull(configurationStatus);
    }
}
