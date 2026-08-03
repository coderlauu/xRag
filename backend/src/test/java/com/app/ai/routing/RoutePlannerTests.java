package com.app.ai.routing;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.model.AiRuntimePolicy;
import com.app.ai.model.AssistantMessage;
import com.app.ai.model.CallOptions;
import com.app.ai.model.Capability;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.DataRegion;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.EmbeddingProfile;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelFeature;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderConfigurationStatus;
import com.app.ai.model.ProviderId;
import com.app.ai.model.QuotaDomain;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.UserMessage;
import com.app.ai.model.ToolDefinition;
import com.app.ai.model.ToolCall;
import com.app.ai.model.ToolResultMessage;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RoutePlannerTests {

    @Test
    void rte01到04_候选固定按default优先级和deploymentId排序() {
        var deployments = new LinkedHashMap<DeploymentId, ModelDeployment>();
        deployments.put(new DeploymentId("z-default"), chatDeployment("z-default", true, 50));
        deployments.put(new DeploymentId("b-priority"), chatDeployment("b-priority", false, 10));
        deployments.put(new DeploymentId("a-priority"), chatDeployment("a-priority", false, 10));
        var snapshot = snapshot(deployments);
        var request = RouteRequest.chat(
                new ChatCommand(
                        List.of(new UserMessage("你好")),
                        List.of(),
                        ServiceTier.BALANCED,
                        CallOptions.defaults()),
                Set.of());
        var planner = new RoutePlanner();

        var orders = java.util.stream.IntStream.range(0, 100)
                .mapToObj(ignored -> planner.plan(snapshot, request, BreakerStateSnapshot.allClosed())
                        .eligibleCandidates().stream()
                        .map(candidate -> candidate.deployment().id().value())
                        .toList())
                .toList();

        assertThat(orders).allSatisfy(order -> assertThat(order)
                .containsExactly("z-default", "a-priority", "b-priority"));
    }

    @Test
    void rte05到12_硬过滤保留全部deployment及其第一个稳定原因() {
        var providers = Map.of(
                new ProviderId("provider-good"), provider(
                        "provider-good", ProviderConfigurationStatus.CONFIGURED, "CN_ALLOWED"),
                new ProviderId("provider-unconfigured"), provider(
                        "provider-unconfigured", ProviderConfigurationStatus.UNCONFIGURED, "CN_ALLOWED"),
                new ProviderId("provider-wrong-region"), provider(
                        "provider-wrong-region", ProviderConfigurationStatus.CONFIGURED, "CN_OTHER"),
                new ProviderId("provider-disallowed"), provider(
                        "provider-disallowed", ProviderConfigurationStatus.CONFIGURED, "CN_ALLOWED"));
        var deployments = new LinkedHashMap<DeploymentId, ModelDeployment>();
        deployments.put(new DeploymentId("disabled"), deployment(
                "disabled", "provider-good", false, Set.of(Capability.CHAT),
                Set.of(ServiceTier.BALANCED), Set.of(ModelFeature.TOOL_CALLING), null));
        deployments.put(new DeploymentId("unconfigured"), deployment(
                "unconfigured", "provider-unconfigured", true, Set.of(Capability.CHAT),
                Set.of(ServiceTier.BALANCED), Set.of(ModelFeature.TOOL_CALLING), null));
        deployments.put(new DeploymentId("wrong-capability"), deployment(
                "wrong-capability", "provider-good", true, Set.of(Capability.RERANK),
                Set.of(ServiceTier.BALANCED), Set.of(ModelFeature.TOOL_CALLING), null));
        deployments.put(new DeploymentId("no-tools"), deployment(
                "no-tools", "provider-good", true, Set.of(Capability.CHAT),
                Set.of(ServiceTier.BALANCED), Set.of(), null));
        deployments.put(new DeploymentId("wrong-tier"), deployment(
                "wrong-tier", "provider-good", true, Set.of(Capability.CHAT),
                Set.of(ServiceTier.QUALITY), Set.of(ModelFeature.TOOL_CALLING), null));
        deployments.put(new DeploymentId("wrong-region"), deployment(
                "wrong-region", "provider-wrong-region", true, Set.of(Capability.CHAT),
                Set.of(ServiceTier.BALANCED), Set.of(ModelFeature.TOOL_CALLING), null));
        deployments.put(new DeploymentId("caller-blocked"), deployment(
                "caller-blocked", "provider-disallowed", true, Set.of(Capability.CHAT),
                Set.of(ServiceTier.BALANCED), Set.of(ModelFeature.TOOL_CALLING), null));
        deployments.put(new DeploymentId("breaker-open"), deployment(
                "breaker-open", "provider-good", true, Set.of(Capability.CHAT),
                Set.of(ServiceTier.BALANCED), Set.of(ModelFeature.TOOL_CALLING), null));
        deployments.put(new DeploymentId("eligible"), deployment(
                "eligible", "provider-good", true, Set.of(Capability.CHAT),
                Set.of(ServiceTier.BALANCED), Set.of(ModelFeature.TOOL_CALLING), null));
        var snapshot = new ModelCatalogSnapshot(
                "test-v1", "a".repeat(64), providers, deployments, runtimePolicy());
        var request = RouteRequest.chat(
                new ChatCommand(
                        List.of(new UserMessage("你好")),
                        List.of(new ToolDefinition("search", "搜索", "{}")),
                        ServiceTier.BALANCED,
                        new CallOptions(
                                null,
                                null,
                                null,
                                Set.of(new DataRegion("CN_ALLOWED")))),
                Set.of(
                        new ProviderId("provider-good"),
                        new ProviderId("provider-unconfigured"),
                        new ProviderId("provider-wrong-region")));

        var plan = new RoutePlanner().plan(
                snapshot,
                request,
                (deploymentId, capability) -> deploymentId.value().equals("breaker-open")
                        ? BreakerSnapshotState.OPEN
                        : BreakerSnapshotState.CLOSED);

        assertThat(plan.candidates()).hasSize(9);
        assertThat(plan.eligibleCandidates())
                .extracting(candidate -> candidate.deployment().id().value())
                .containsExactly("eligible");
        assertThat(plan.candidates()).extracting(
                candidate -> candidate.deployment().id().value(),
                RouteCandidate::rejectionReason)
                .contains(
                        org.assertj.core.groups.Tuple.tuple("disabled", CandidateRejectionReason.DISABLED),
                        org.assertj.core.groups.Tuple.tuple("unconfigured", CandidateRejectionReason.CONFIG_INCOMPLETE),
                        org.assertj.core.groups.Tuple.tuple("wrong-capability", CandidateRejectionReason.CAPABILITY_UNSUPPORTED),
                        org.assertj.core.groups.Tuple.tuple("no-tools", CandidateRejectionReason.FEATURE_UNSUPPORTED),
                        org.assertj.core.groups.Tuple.tuple("wrong-tier", CandidateRejectionReason.SERVICE_TIER_MISMATCH),
                        org.assertj.core.groups.Tuple.tuple("wrong-region", CandidateRejectionReason.DATA_BOUNDARY_MISMATCH),
                        org.assertj.core.groups.Tuple.tuple("caller-blocked", CandidateRejectionReason.CALLER_CONSTRAINT_MISMATCH),
                        org.assertj.core.groups.Tuple.tuple("breaker-open", CandidateRejectionReason.BREAKER_OPEN));
    }

    @Test
    void rte17_Embedding按所有等价候选的最小batchLimit冻结workUnit() {
        var deployments = new LinkedHashMap<DeploymentId, ModelDeployment>();
        deployments.put(new DeploymentId("embedding-a"), embeddingDeployment("embedding-a", 10));
        deployments.put(new DeploymentId("embedding-b"), embeddingDeployment("embedding-b", 6));
        var snapshot = snapshot(deployments);
        var request = RouteRequest.embedding(
                new ModelIdentity("embed-v1"), 13, CallOptions.defaults(), Set.of());

        var plan = new RoutePlanner().plan(snapshot, request, BreakerStateSnapshot.allClosed());

        assertThat(plan.eligibleCandidates()).hasSize(2);
        assertThat(plan.workUnits()).containsExactly(
                new WorkUnitRange(0, 0, 6),
                new WorkUnitRange(1, 6, 12),
                new WorkUnitRange(2, 12, 13));
    }

    @Test
    void rte10_规划只读取一次breaker快照且不申请运行时许可() {
        var snapshot = snapshot(Map.of(
                new DeploymentId("chat-a"), chatDeployment("chat-a", true, 10)));
        var reads = new AtomicInteger();
        BreakerStateSnapshot changingSource = (deploymentId, capability) ->
                reads.getAndIncrement() == 0 ? BreakerSnapshotState.CLOSED : BreakerSnapshotState.OPEN;
        var request = RouteRequest.chat(
                new ChatCommand(
                        List.of(new UserMessage("你好")),
                        List.of(),
                        ServiceTier.BALANCED,
                        CallOptions.defaults()),
                Set.of());

        var plan = new RoutePlanner().plan(snapshot, request, changingSource);

        assertThat(reads).hasValue(1);
        assertThat(plan.eligibleCandidates()).hasSize(1);
        assertThat(plan.candidates().get(0).breakerStateSnapshot()).isEqualTo(BreakerSnapshotState.CLOSED);
    }

    @Test
    void rte13_配置源变化不改变已经生成的RoutePlan() {
        var source = new LinkedHashMap<DeploymentId, ModelDeployment>();
        source.put(new DeploymentId("chat-a"), chatDeployment("chat-a", true, 10));
        var snapshot = snapshot(source);
        var request = RouteRequest.chat(
                new ChatCommand(
                        List.of(new UserMessage("你好")),
                        List.of(),
                        ServiceTier.BALANCED,
                        CallOptions.defaults()),
                Set.of());

        var plan = new RoutePlanner().plan(snapshot, request, BreakerStateSnapshot.allClosed());
        source.clear();
        source.put(new DeploymentId("chat-b"), chatDeployment("chat-b", true, 1));

        assertThat(plan.eligibleCandidates())
                .extracting(candidate -> candidate.deployment().id().value())
                .containsExactly("chat-a");
        assertThat(plan.candidates()).hasSize(1);
    }

    @Test
    void rte06_工具定义助手调用历史和工具结果都会自动要求ToolCalling() {
        var noToolDeployment = deployment(
                "chat-no-tools",
                "provider-a",
                true,
                Set.of(Capability.CHAT),
                Set.of(ServiceTier.BALANCED),
                Set.of(),
                null);
        var snapshot = snapshot(Map.of(noToolDeployment.id(), noToolDeployment));
        List<ChatCommand> commands = List.of(
                new ChatCommand(
                        List.of(new UserMessage("查找")),
                        List.of(new ToolDefinition("search", "搜索", "{}")),
                        ServiceTier.BALANCED,
                        CallOptions.defaults()),
                new ChatCommand(
                        List.of(new AssistantMessage(
                                null,
                                null,
                                List.of(new ToolCall("call-1", "search", "{}")))),
                        List.of(),
                        ServiceTier.BALANCED,
                        CallOptions.defaults()),
                new ChatCommand(
                        List.of(new ToolResultMessage("call-1", "结果")),
                        List.of(),
                        ServiceTier.BALANCED,
                        CallOptions.defaults()));

        assertThat(commands).allSatisfy(command -> {
            var plan = new RoutePlanner().plan(
                    snapshot,
                    RouteRequest.chat(command, Set.of()),
                    BreakerStateSnapshot.allClosed());
            assertThat(plan.eligibleCandidates()).isEmpty();
            assertThat(plan.candidates().get(0).rejectionReason())
                    .isEqualTo(CandidateRejectionReason.FEATURE_UNSUPPORTED);
        });
        assertThat(RouteRequest.class.getMethods())
                .extracting(java.lang.reflect.Method::getName)
                .noneMatch(name -> name.equals("requiredFeatures") || name.equals("toolCallingRequired"));
    }

    private static ModelDeployment chatDeployment(String id, boolean defaultCandidate, int priority) {
        return new ModelDeployment(
                new DeploymentId(id),
                new ProviderId("provider-a"),
                "chat-model",
                new ModelIdentity("chat-model-v1"),
                Set.of(Capability.CHAT),
                Set.of(ServiceTier.BALANCED),
                defaultCandidate,
                priority,
                true,
                null,
                Set.of(ModelFeature.TOOL_CALLING));
    }

    private static ModelDeployment deployment(
            String id,
            String providerId,
            boolean enabled,
            Set<Capability> capabilities,
            Set<ServiceTier> tiers,
            Set<ModelFeature> features,
            com.app.ai.model.EmbeddingProfile embedding) {
        return new ModelDeployment(
                new DeploymentId(id),
                new ProviderId(providerId),
                id,
                new ModelIdentity(id + "-identity"),
                capabilities,
                tiers,
                false,
                10,
                enabled,
                embedding,
                features);
    }

    private static ModelDeployment embeddingDeployment(String id, int maxBatchItems) {
        return new ModelDeployment(
                new DeploymentId(id),
                new ProviderId("provider-a"),
                "embed-model",
                new ModelIdentity("embed-v1"),
                Set.of(Capability.EMBEDDING),
                Set.of(),
                false,
                10,
                true,
                new EmbeddingProfile(false, "artifact-v1", 2, maxBatchItems, 8192, "RAW_UTF8_V1"),
                Set.of());
    }

    private static ModelCatalogSnapshot snapshot(Map<DeploymentId, ModelDeployment> deployments) {
        var provider = provider("provider-a", ProviderConfigurationStatus.CONFIGURED, "CN_TEST");
        return new ModelCatalogSnapshot(
                "test-v1",
                "a".repeat(64),
                Map.of(provider.id(), provider),
                deployments,
                runtimePolicy());
    }

    private static ModelProvider provider(
            String id, ProviderConfigurationStatus status, String region) {
        return new ModelProvider(
                new ProviderId(id),
                "fake",
                "Fake " + id,
                URI.create("https://example.test/v1"),
                "AI_SECRET",
                new DataRegion(region),
                new QuotaDomain("quota-" + id),
                status);
    }

    private static AiRuntimePolicy runtimePolicy() {
        var limits = new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(30), 3, 2);
        return new AiRuntimePolicy(
                limits,
                limits,
                limits,
                limits,
                new AiRuntimePolicy.StreamLimits(32, 65_536, 65_536),
                new AiRuntimePolicy.BreakerLimits(
                        20, 5, 50, 50, Duration.ofSeconds(30), 2, Duration.ofSeconds(30)));
    }
}
