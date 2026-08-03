package com.app.ai.provider;

import com.app.ai.model.ModelDeployment;

public interface RerankProvider {

    ProviderRerankResult rerank(
            ModelDeployment deployment,
            ProviderRerankRequest request,
            ProviderAttemptContext context);
}
