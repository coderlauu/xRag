package com.app.ai.provider;

import com.app.ai.model.ModelDeployment;

public interface ChatProvider {

    ProviderChatResult complete(
            ModelDeployment deployment,
            ProviderChatRequest request,
            ProviderAttemptContext context);
}
