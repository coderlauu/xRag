package com.app.ai.provider;

import com.app.ai.model.RerankCandidate;
import java.util.List;

public record ProviderRerankRequest(
        String query,
        List<RerankCandidate> candidates,
        int topN) {

    public ProviderRerankRequest {
        candidates = List.copyOf(candidates);
    }
}
