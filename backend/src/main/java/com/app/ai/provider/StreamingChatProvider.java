package com.app.ai.provider;

import com.app.ai.model.ModelDeployment;
import reactor.core.publisher.Flux;

public interface StreamingChatProvider {

    Flux<ProviderChatEvent> stream(
            ModelDeployment deployment,
            ProviderChatRequest request,
            ProviderAttemptContext context);
}
