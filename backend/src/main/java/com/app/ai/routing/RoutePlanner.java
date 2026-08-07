package com.app.ai.routing;

import com.app.ai.model.Capability;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderConfigurationStatus;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class RoutePlanner {

    private static final Comparator<ModelDeployment> ELIGIBLE_ORDER = Comparator
            .comparing(ModelDeployment::defaultCandidate).reversed()
            .thenComparingInt(ModelDeployment::priority)
            .thenComparing(ModelDeployment::id);

    public RoutePlan plan(
            ModelCatalogSnapshot snapshot,
            RouteRequest request,
            BreakerStateSnapshot breakerStates) {
        List<ModelDeployment> orderedDeployments = snapshot.deployments().values().stream()
                .sorted(Comparator.comparing(ModelDeployment::id))
                .toList();
        Map<com.app.ai.model.DeploymentId, BreakerSnapshotState> breakerSnapshot = new HashMap<>();
        Map<com.app.ai.model.DeploymentId, CandidateRejectionReason> rejections = new HashMap<>();
        for (ModelDeployment deployment : orderedDeployments) {
            BreakerSnapshotState breakerState = breakerStates.stateOf(deployment.id(), request.capability());
            breakerSnapshot.put(deployment.id(), breakerState);
            CandidateRejectionReason reason = rejection(snapshot, request, breakerState, deployment);
            if (reason != null) {
                rejections.put(deployment.id(), reason);
            }
        }
        List<ModelDeployment> eligibleDeployments = orderedDeployments.stream()
                .filter(deployment -> !rejections.containsKey(deployment.id()))
                .sorted(ELIGIBLE_ORDER)
                .toList();
        Map<com.app.ai.model.DeploymentId, Integer> plannedOrders = new HashMap<>();
        for (int index = 0; index < eligibleDeployments.size(); index++) {
            plannedOrders.put(eligibleDeployments.get(index).id(), index);
        }

        List<RouteCandidate> candidates = new ArrayList<>(orderedDeployments.size());
        for (int ordinal = 0; ordinal < orderedDeployments.size(); ordinal++) {
            ModelDeployment deployment = orderedDeployments.get(ordinal);
            CandidateRejectionReason rejection = rejections.get(deployment.id());
            Integer plannedOrder = plannedOrders.get(deployment.id());
            candidates.add(new RouteCandidate(
                    ordinal,
                    deployment,
                    rejection == null ? CandidateDecision.ELIGIBLE : CandidateDecision.FILTERED,
                    rejection,
                    plannedOrder,
                    breakerSnapshot.get(deployment.id())));
        }
        List<RouteCandidate> eligible = candidates.stream()
                .filter(candidate -> candidate.decision() == CandidateDecision.ELIGIBLE)
                .sorted(Comparator.comparing(RouteCandidate::plannedOrder))
                .toList();
        List<WorkUnitRange> workUnits = workUnits(request, eligible);
        return new RoutePlan(
                snapshot.configFingerprint(), request.capability(), candidates, eligible, workUnits);
    }

    private static List<WorkUnitRange> workUnits(
            RouteRequest request, List<RouteCandidate> eligible) {
        if (request.capability() != Capability.EMBEDDING
                || request.embeddingInputCount() == 0
                || eligible.isEmpty()) {
            return List.of();
        }
        int batchSize = eligible.stream()
                .map(RouteCandidate::deployment)
                .map(ModelDeployment::embedding)
                .mapToInt(profile -> profile.maxBatchItems())
                .min()
                .orElseThrow();
        List<WorkUnitRange> ranges = new ArrayList<>();
        for (int from = 0, workUnit = 0; from < request.embeddingInputCount(); from += batchSize, workUnit++) {
            ranges.add(new WorkUnitRange(
                    workUnit,
                    from,
                    Math.min(request.embeddingInputCount(), from + batchSize)));
        }
        return List.copyOf(ranges);
    }

    private static CandidateRejectionReason rejection(
            ModelCatalogSnapshot snapshot,
            RouteRequest request,
            BreakerSnapshotState breakerState,
            ModelDeployment deployment) {
        if (!deployment.enabled()) {
            return CandidateRejectionReason.DISABLED;
        }
        ModelProvider provider = snapshot.providers().get(deployment.providerId());
        if (provider.configurationStatus() == ProviderConfigurationStatus.UNCONFIGURED) {
            return CandidateRejectionReason.CONFIG_INCOMPLETE;
        }
        if (!deployment.capabilities().contains(request.capability())) {
            return CandidateRejectionReason.CAPABILITY_UNSUPPORTED;
        }
        if (request.toolCallingRequired()
                && !deployment.features().contains(com.app.ai.model.ModelFeature.TOOL_CALLING)) {
            return CandidateRejectionReason.FEATURE_UNSUPPORTED;
        }
        if (request.capability() == Capability.EMBEDDING
                && !deployment.modelIdentity().equals(request.requiredModelIdentity())) {
            return CandidateRejectionReason.MODEL_IDENTITY_MISMATCH;
        }
        if (request.capability() != Capability.EMBEDDING
                && !deployment.tiers().contains(request.serviceTier())) {
            return CandidateRejectionReason.SERVICE_TIER_MISMATCH;
        }
        if (!request.allowedDataRegions().isEmpty()
                && !request.allowedDataRegions().contains(provider.dataRegion())) {
            return CandidateRejectionReason.DATA_BOUNDARY_MISMATCH;
        }
        if (!request.allowedProviders().isEmpty()
                && !request.allowedProviders().contains(provider.id())) {
            return CandidateRejectionReason.CALLER_CONSTRAINT_MISMATCH;
        }
        if (request.capability() == Capability.RERANK
                && deployment.rerank() != null
                && request.rerankDocumentCount() > deployment.rerank().maxDocuments()) {
            return CandidateRejectionReason.CALLER_CONSTRAINT_MISMATCH;
        }
        if (breakerState == BreakerSnapshotState.OPEN) {
            return CandidateRejectionReason.BREAKER_OPEN;
        }
        return null;
    }
}
