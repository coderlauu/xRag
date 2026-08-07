package com.app.ai.routing;

import com.app.ai.model.AssistantMessage;
import com.app.ai.model.CallOptions;
import com.app.ai.model.Capability;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.DataRegion;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ProviderId;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.ToolResultMessage;
import java.util.Objects;
import java.util.Set;

public final class RouteRequest {

    private final Capability capability;
    private final ServiceTier serviceTier;
    private final ModelIdentity requiredModelIdentity;
    private final Set<DataRegion> allowedDataRegions;
    private final Set<ProviderId> allowedProviders;
    private final boolean toolCallingRequired;
    private final int embeddingInputCount;
    private final int rerankDocumentCount;

    private RouteRequest(
            Capability capability,
            ServiceTier serviceTier,
            ModelIdentity requiredModelIdentity,
            Set<DataRegion> allowedDataRegions,
            Set<ProviderId> allowedProviders,
            boolean toolCallingRequired,
            int embeddingInputCount,
            int rerankDocumentCount) {
        this.capability = Objects.requireNonNull(capability);
        this.serviceTier = serviceTier;
        this.requiredModelIdentity = requiredModelIdentity;
        this.allowedDataRegions = Set.copyOf(allowedDataRegions);
        this.allowedProviders = Set.copyOf(allowedProviders);
        this.toolCallingRequired = toolCallingRequired;
        this.embeddingInputCount = embeddingInputCount;
        this.rerankDocumentCount = rerankDocumentCount;
    }

    public static RouteRequest chat(ChatCommand command, Set<ProviderId> allowedProviders) {
        Objects.requireNonNull(command);
        boolean requiresTools = !command.tools().isEmpty()
                || command.messages().stream().anyMatch(message ->
                        message instanceof ToolResultMessage
                                || (message instanceof AssistantMessage assistant
                                        && !assistant.toolCalls().isEmpty()));
        return new RouteRequest(
                Capability.CHAT,
                command.tier(),
                null,
                command.options().allowedDataRegions(),
                allowedProviders,
                requiresTools,
                0,
                0);
    }

    public static RouteRequest capability(
            Capability capability,
            ServiceTier tier,
            CallOptions options,
            Set<ProviderId> allowedProviders) {
        return new RouteRequest(
                capability,
                tier == null ? ServiceTier.BALANCED : tier,
                null,
                options == null ? Set.of() : options.allowedDataRegions(),
                allowedProviders,
                false,
                0,
                0);
    }

    /** Rerank 的候选数参与路由：超过某个 deployment 文档上限的请求，那个 deployment 不算合格候选。 */
    public static RouteRequest rerank(
            ServiceTier tier,
            int documentCount,
            CallOptions options,
            Set<ProviderId> allowedProviders) {
        if (documentCount < 0) {
            throw new IllegalArgumentException("rerank document count must be non-negative");
        }
        return new RouteRequest(
                Capability.RERANK,
                tier == null ? ServiceTier.BALANCED : tier,
                null,
                options == null ? Set.of() : options.allowedDataRegions(),
                allowedProviders,
                false,
                0,
                documentCount);
    }

    public static RouteRequest embedding(
            ModelIdentity identity,
            int inputCount,
            CallOptions options,
            Set<ProviderId> allowedProviders) {
        if (inputCount < 0) {
            throw new IllegalArgumentException("embedding input count must be non-negative");
        }
        return new RouteRequest(
                Capability.EMBEDDING,
                null,
                Objects.requireNonNull(identity),
                options == null ? Set.of() : options.allowedDataRegions(),
                allowedProviders,
                false,
                inputCount,
                0);
    }

    public Capability capability() {
        return capability;
    }

    public ServiceTier serviceTier() {
        return serviceTier;
    }

    public ModelIdentity requiredModelIdentity() {
        return requiredModelIdentity;
    }

    public Set<DataRegion> allowedDataRegions() {
        return allowedDataRegions;
    }

    public Set<ProviderId> allowedProviders() {
        return allowedProviders;
    }

    boolean toolCallingRequired() {
        return toolCallingRequired;
    }

    public int embeddingInputCount() {
        return embeddingInputCount;
    }

    public int rerankDocumentCount() {
        return rerankDocumentCount;
    }
}
