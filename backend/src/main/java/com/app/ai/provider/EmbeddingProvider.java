package com.app.ai.provider;

import com.app.ai.model.ModelDeployment;
import java.util.List;

public interface EmbeddingProvider {

    ProviderEmbeddingResult embed(
            ModelDeployment deployment,
            List<String> texts,
            ProviderAttemptContext context);
}
