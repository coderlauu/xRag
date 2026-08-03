package com.app.ai.provider;

import java.util.List;

public record ProviderRerankResult(
        List<ProviderRankedItem> items,
        String providerRequestIdMasked) {

    public ProviderRerankResult {
        items = List.copyOf(items);
    }
}
