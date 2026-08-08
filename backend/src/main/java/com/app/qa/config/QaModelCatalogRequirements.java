package com.app.qa.config;

import com.app.ai.model.Capability;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ServiceTier;
import java.util.Collection;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

@Component
public final class QaModelCatalogRequirements implements InitializingBean {

    private final ModelCatalogSnapshot catalog;

    public QaModelCatalogRequirements(ModelCatalogSnapshot catalog) {
        this.catalog = catalog;
    }

    @Override
    public void afterPropertiesSet() {
        requireBalancedChat(catalog.deployments().values());
    }

    public static void requireBalancedChat(Collection<ModelDeployment> deployments) {
        boolean available = deployments.stream().anyMatch(QaModelCatalogRequirements::isUsableBalancedChat);
        if (!available) {
            throw new IllegalStateException(
                    "app.ai.deployments must contain an enabled BALANCED Chat sampling profile for Phase 3A");
        }
    }

    private static boolean isUsableBalancedChat(ModelDeployment deployment) {
        if (!deployment.enabled()
                || !deployment.capabilities().contains(Capability.CHAT)
                || !deployment.tiers().contains(ServiceTier.BALANCED)
                || deployment.chat() == null) {
            return false;
        }
        try {
            deployment.chat().sampling(ServiceTier.BALANCED);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
