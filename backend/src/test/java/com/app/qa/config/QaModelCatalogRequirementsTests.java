package com.app.qa.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.app.ai.model.Capability;
import com.app.ai.model.ChatProfile;
import com.app.ai.model.ChatSamplingParameters;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ProviderId;
import com.app.ai.model.ServiceTier;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QaModelCatalogRequirementsTests {

    @Test
    void 启用的BalancedChat必须有对应采样profile() {
        assertThatThrownBy(() -> QaModelCatalogRequirements.requireBalancedChat(
                        Set.of(chatDeployment(null))))
                .hasMessageContaining("app.ai.deployments")
                .hasMessageContaining("BALANCED Chat sampling profile");

        var profile = new ChatProfile(Map.of(
                ServiceTier.BALANCED,
                new ChatSamplingParameters(0.1, 0.3)));
        assertThatCode(() -> QaModelCatalogRequirements.requireBalancedChat(
                        Set.of(chatDeployment(profile))))
                .doesNotThrowAnyException();
    }

    private static ModelDeployment chatDeployment(ChatProfile profile) {
        return new ModelDeployment(
                new DeploymentId("qa-chat"),
                new ProviderId("provider-a"),
                "chat-model",
                new ModelIdentity("chat-model"),
                Set.of(Capability.CHAT),
                Set.of(ServiceTier.BALANCED),
                true,
                10,
                true,
                null,
                Set.of(),
                null,
                profile);
    }
}
