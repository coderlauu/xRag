package com.app.ai.rerank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.app.ai.breaker.Resilience4jModelBreakerRegistry;
import com.app.ai.diagnostic.AttemptKind;
import com.app.ai.diagnostic.AttemptOutcome;
import com.app.ai.diagnostic.BreakerState;
import com.app.ai.diagnostic.CallOutcome;
import com.app.ai.diagnostic.CompletedCallDiagnostic;
import com.app.ai.diagnostic.DiagnosticRecorder;
import com.app.ai.diagnostic.RouteRejectionReason;
import com.app.ai.model.AiRuntimePolicy;
import com.app.ai.model.CallOptions;
import com.app.ai.model.CancellationRegistration;
import com.app.ai.model.CancellationToken;
import com.app.ai.model.Capability;
import com.app.ai.model.DataRegion;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.FailurePhase;
import com.app.ai.model.ModelCallException;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderConfigurationStatus;
import com.app.ai.model.ProviderId;
import com.app.ai.model.QuotaDomain;
import com.app.ai.model.RerankCandidate;
import com.app.ai.model.RerankCommand;
import com.app.ai.model.RerankDegradationReason;
import com.app.ai.model.RerankProfile;
import com.app.ai.model.RerankResult;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.TerminationReason;
import com.app.ai.provider.AttemptFailureType;
import com.app.ai.provider.ProgrammableFakeProvider;
import com.app.ai.provider.ProviderFailureException;
import com.app.ai.provider.ProviderRankedItem;
import com.app.ai.provider.ProviderRerankResult;
import com.app.ai.provider.RerankProvider;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** RRK-01~RRK-12 与 DIA-05。 */
class RoutedRerankClientTests {

    private static final ProviderId PROVIDER_A = new ProviderId("provider-a");
    private static final ProviderId PROVIDER_B = new ProviderId("provider-b");
    private static final DeploymentId RERANK_A = new DeploymentId("rerank-a");
    private static final DeploymentId RERANK_B = new DeploymentId("rerank-b");

    private final List<CompletedCallDiagnostic> recorded = new ArrayList<>();
    private final DiagnosticRecorder recorder = new CapturingRecorder(recorded);

    @Test
    void rrk01_真实rerank按provider下标映射回稳定candidateId() {
        var provider = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.rerank(
                (deployment, request, context) -> new ProviderRerankResult(
                        List.of(new ProviderRankedItem(2, 0.91), new ProviderRankedItem(0, 0.42)),
                        "req-***-1")));

        RerankResult result = client(catalog(), Map.of(PROVIDER_A, provider))
                .rerank(command(3, 2));

        assertThat(result.rerankApplied()).isTrue();
        assertThat(result.degradationReason()).isNull();
        assertThat(result.items())
                .extracting(item -> item.id(), item -> item.originalIndex(), item -> item.relevanceScore())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("doc-2", 2, 0.91),
                        org.assertj.core.groups.Tuple.tuple("doc-0", 0, 0.42));
        assertThat(result.execution().actualExecution().deploymentId()).isEqualTo(RERANK_A);
        assertThat(recorded).singleElement().satisfies(call -> {
            assertThat(call.outcome()).isEqualTo(CallOutcome.SUCCEEDED);
            assertThat(call.rerankApplied()).isTrue();
            assertThat(call.degradedReason()).isNull();
            assertThat(call.usage().rerankDocumentCount()).isEqualTo(3);
            assertThat(call.usage().rerankTopN()).isEqualTo(2);
        });
    }

    @Test
    void rrk02_topN为1时只返回一条() {
        var provider = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.rerank(
                (deployment, request, context) -> new ProviderRerankResult(
                        List.of(new ProviderRankedItem(1, 0.77)), null)));

        RerankResult result = client(catalog(), Map.of(PROVIDER_A, provider))
                .rerank(command(3, 1));

        assertThat(result.items()).singleElement()
                .satisfies(item -> assertThat(item.id()).isEqualTo("doc-1"));
    }

    @Test
    void rrk03_candidateId重复在调用前就被拒绝() {
        var provider = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.rerank(
                (deployment, request, context) -> {
                    throw new AssertionError("非法请求不该发到 Provider");
                }));
        var command = new RerankCommand(
                "查询",
                List.of(new RerankCandidate("dup", "甲"), new RerankCandidate("dup", "乙")),
                1,
                ServiceTier.BALANCED,
                CallOptions.defaults());

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client(catalog(), Map.of(PROVIDER_A, provider)).rerank(command))
                .satisfies(failure -> assertThat(failure.terminalCause().errorType())
                        .isEqualTo(ModelErrorType.INVALID_REQUEST));
        assertThat(provider.rerankRequests()).isEmpty();
        assertThat(recorded).singleElement().satisfies(call -> {
            assertThat(call.outcome()).isEqualTo(CallOutcome.FAILED);
            assertThat(call.terminationReason()).isEqualTo(TerminationReason.INVALID_REQUEST);
            assertThat(call.attempts()).isEmpty();
        });
    }

    @Test
    void rrk04_topN越界在调用前就被拒绝() {
        var provider = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.rerank(
                (deployment, request, context) -> {
                    throw new AssertionError("非法请求不该发到 Provider");
                }));

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client(catalog(), Map.of(PROVIDER_A, provider))
                        .rerank(command(3, 4)))
                .satisfies(failure -> assertThat(failure.terminalCause().errorType())
                        .isEqualTo(ModelErrorType.INVALID_REQUEST));
        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client(catalog(), Map.of(PROVIDER_A, provider))
                        .rerank(command(3, 0)));
        assertThat(provider.rerankRequests()).isEmpty();
    }

    @Test
    void rrk05_provider下标越界或重复算协议违约可切候选且计入breaker() {
        var broken = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.rerank(
                (deployment, request, context) -> new ProviderRerankResult(
                        List.of(new ProviderRankedItem(9, 0.5)), null)));
        var healthy = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.rerank(
                (deployment, request, context) -> new ProviderRerankResult(
                        List.of(new ProviderRankedItem(0, 0.8)), null)));
        var registry = registry();

        RerankResult result = client(
                twoProviderCatalog(), Map.of(PROVIDER_A, broken, PROVIDER_B, healthy), registry)
                .rerank(command(3, 1));

        assertThat(result.rerankApplied()).isTrue();
        assertThat(result.execution().actualExecution().deploymentId()).isEqualTo(RERANK_B);
        assertThat(recorded).singleElement().satisfies(call ->
                assertThat(call.attempts()).first().satisfies(attempt -> {
                    assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.FAILED);
                    assertThat(attempt.normalizedErrorType())
                            .isEqualTo(ModelErrorType.PROTOCOL_CONTRACT);
                    assertThat(attempt.failurePhase())
                            .isEqualTo(FailurePhase.PROVIDER_RESPONSE_VALIDATION);
                }));
        // 协议违约按失败结算，滑动窗口里必须看得见，不能因为"是我们自己判的"就不计。
        assertThat(registry.snapshot(targetOf(RERANK_A, PROVIDER_A), Capability.RERANK).failedCalls())
                .isEqualTo(1);
    }

    @Test
    void rrk06_目录未声明rerank能力时降级为原顺序noop() {
        RerankResult result = client(chatOnlyCatalog(), Map.of()).rerank(command(3, 2));

        assertThat(result.rerankApplied()).isFalse();
        assertThat(result.degradationReason())
                .isEqualTo(RerankDegradationReason.CAPABILITY_NOT_DECLARED);
        assertThat(result.items())
                .extracting(item -> item.id())
                .containsExactly("doc-0", "doc-1");
    }

    @Test
    void rrk07_可恢复候选全部耗尽后降级为noop并保留失败attempt() {
        var failing = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.rerank(
                (deployment, request, context) -> {
                    throw ProviderFailureException.of(AttemptFailureType.SERVER_ERROR_RESPONSE);
                }));

        RerankResult result = client(
                twoProviderCatalog(), Map.of(PROVIDER_A, failing, PROVIDER_B, failing))
                .rerank(command(3, 2));

        assertThat(result.rerankApplied()).isFalse();
        assertThat(result.degradationReason())
                .isEqualTo(RerankDegradationReason.RECOVERABLE_CANDIDATES_EXHAUSTED);
        assertThat(result.items()).extracting(item -> item.id()).containsExactly("doc-0", "doc-1");
        assertThat(recorded).singleElement().satisfies(call -> {
            assertThat(call.outcome()).isEqualTo(CallOutcome.DEGRADED);
            // 失败 attempt 必须保留下来，否则页面会以为这次降级什么都没试过。
            assertThat(call.attempts()).isNotEmpty();
            assertThat(call.attempts()).allSatisfy(attempt ->
                    assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.FAILED));
        });
    }

    @Test
    void rrk08_声明了rerank却缺secret时直接失败不被noop掩盖() {
        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client(unconfiguredCatalog(), Map.of()).rerank(command(3, 2)))
                .satisfies(failure -> {
                    assertThat(failure.terminalCause().errorType()).isEqualTo(ModelErrorType.AUTHENTICATION);
                    assertThat(failure.terminalCause().terminationReason())
                            .isEqualTo(TerminationReason.NON_RECOVERABLE_ERROR);
                });
        assertThat(recorded).singleElement().satisfies(call -> {
            assertThat(call.outcome()).isEqualTo(CallOutcome.FAILED);
            assertThat(call.rerankApplied()).isNull();
            assertThat(call.degradedReason()).isNull();
        });
    }

    @Test
    void rrk09_调用方取消时按取消失败而不是降级() {
        var provider = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.rerank(
                (deployment, request, context) -> new ProviderRerankResult(
                        List.of(new ProviderRankedItem(0, 0.5)), null)));
        var command = new RerankCommand(
                "查询",
                candidates(3),
                2,
                ServiceTier.BALANCED,
                new CallOptions(null, null, cancelled(), Set.of()));

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client(catalog(), Map.of(PROVIDER_A, provider)).rerank(command))
                .satisfies(failure ->
                        assertThat(failure.terminalCause().errorType()).isEqualTo(ModelErrorType.CANCELED));
        assertThat(recorded).singleElement().satisfies(call ->
                assertThat(call.outcome()).isEqualTo(CallOutcome.CANCELED));
    }

    @Test
    void rrk10_noop不产生attempt不改变breaker且不伪造分数() {
        var registry = registry();

        RerankResult result = client(chatOnlyCatalog(), Map.of(), registry).rerank(command(3, 2));

        assertThat(result.rerankApplied()).isFalse();
        assertThat(result.degradationReason()).isNotNull();
        assertThat(result.items()).allSatisfy(item ->
                assertThat(item.relevanceScore()).isNull());
        assertThat(result.execution().actualExecution()).isNull();
        assertThat(result.execution().attemptCount()).isZero();
        assertThat(registry.snapshots()).allSatisfy(snapshot ->
                assertThat(snapshot.state()).isEqualTo(BreakerState.CLOSED));
        assertThat(recorded).singleElement().satisfies(call ->
                assertThat(call.attempts()).isEmpty());
    }

    @Test
    void rrk11_候选数超过deployment文档上限时该候选被显式过滤() {
        // rerank-a 的 maxDocuments=3，这里发 4 条。
        RerankResult result = client(catalog(), Map.of()).rerank(command(4, 2));

        assertThat(result.rerankApplied()).isFalse();
        assertThat(result.degradationReason())
                .isEqualTo(RerankDegradationReason.NO_ELIGIBLE_CANDIDATE);
        // "不静默"的含义是过滤原因进了诊断，而不是悄悄截断候选后照常排序。
        assertThat(recorded).singleElement().satisfies(call ->
                assertThat(call.candidates()).anySatisfy(candidate ->
                        assertThat(candidate.rejectionReason())
                                .isEqualTo(RouteRejectionReason.CALLER_CONSTRAINT_MISMATCH)));
    }

    @Test
    void rrk12_声明了能力但等级不匹配时降级为无合格候选而不是未声明能力() {
        RerankResult result = client(catalog(), Map.of())
                .rerank(new RerankCommand(
                        "查询", candidates(3), 2, ServiceTier.QUALITY, CallOptions.defaults()));

        assertThat(result.degradationReason())
                .isEqualTo(RerankDegradationReason.NO_ELIGIBLE_CANDIDATE);
        assertThat(recorded).singleElement().satisfies(call ->
                assertThat(call.candidates()).anySatisfy(candidate ->
                        assertThat(candidate.rejectionReason())
                                .isEqualTo(RouteRejectionReason.SERVICE_TIER_MISMATCH)));
    }

    @Test
    void dia05_noop持久化为degraded且三个字段始终成对() {
        client(chatOnlyCatalog(), Map.of()).rerank(command(3, 2));

        assertThat(recorded).singleElement().satisfies(call -> {
            assertThat(call.capability()).isEqualTo(Capability.RERANK);
            assertThat(call.outcome()).isEqualTo(CallOutcome.DEGRADED);
            assertThat(call.rerankApplied()).isFalse();
            assertThat(call.degradedReason())
                    .isEqualTo(RerankDegradationReason.CAPABILITY_NOT_DECLARED);
            assertThat(call.terminationReason()).isEqualTo(TerminationReason.NOOP_DEGRADED);
            // DEGRADED 不贡献执行事实：actual 三字段为 null、contributor 为 0。
            assertThat(call.executionSummary().actualExecution()).isNull();
            assertThat(call.executionSummary().contributingDeploymentCount()).isZero();
        });
    }

    @Test
    void 诊断不保存query与候选正文() {
        var provider = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.rerank(
                (deployment, request, context) -> new ProviderRerankResult(
                        List.of(new ProviderRankedItem(0, 0.5)), null)));

        client(catalog(), Map.of(PROVIDER_A, provider)).rerank(new RerankCommand(
                "机密查询串",
                List.of(new RerankCandidate("doc-0", "机密正文串")),
                1,
                ServiceTier.BALANCED,
                CallOptions.defaults()));

        assertThat(recorded).singleElement().satisfies(call -> {
            assertThat(call.toString()).doesNotContain("机密查询串");
            assertThat(call.toString()).doesNotContain("机密正文串");
        });
    }

    private RoutedRerankClient client(
            ModelCatalogSnapshot catalog, Map<ProviderId, RerankProvider> providers) {
        return client(catalog, providers, registry());
    }

    private RoutedRerankClient client(
            ModelCatalogSnapshot catalog,
            Map<ProviderId, RerankProvider> providers,
            Resilience4jModelBreakerRegistry registry) {
        return new RoutedRerankClient(catalog, providers, registry, recorder);
    }

    private static Resilience4jModelBreakerRegistry registry() {
        return new Resilience4jModelBreakerRegistry(
                runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
    }

    private static RerankCommand command(int candidateCount, int topN) {
        return new RerankCommand(
                "查询", candidates(candidateCount), topN, ServiceTier.BALANCED, CallOptions.defaults());
    }

    private static List<RerankCandidate> candidates(int count) {
        List<RerankCandidate> candidates = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            candidates.add(new RerankCandidate("doc-" + index, "正文 " + index));
        }
        return candidates;
    }

    private static ExecutionTarget targetOf(DeploymentId deploymentId, ProviderId providerId) {
        return new ExecutionTarget(
                providerId, deploymentId, new ModelIdentity(deploymentId.value() + "-v1"));
    }

    /** 一个 CONFIGURED 的 rerank deployment，maxDocuments=3、只支持 BALANCED。 */
    private static ModelCatalogSnapshot catalog() {
        var providers = new LinkedHashMap<ProviderId, ModelProvider>();
        providers.put(PROVIDER_A, provider(PROVIDER_A, ProviderConfigurationStatus.CONFIGURED));
        var deployments = new LinkedHashMap<DeploymentId, ModelDeployment>();
        deployments.put(RERANK_A, rerankDeployment(RERANK_A, PROVIDER_A, 10, 3));
        return snapshot(providers, deployments);
    }

    private static ModelCatalogSnapshot twoProviderCatalog() {
        var providers = new LinkedHashMap<ProviderId, ModelProvider>();
        providers.put(PROVIDER_A, provider(PROVIDER_A, ProviderConfigurationStatus.CONFIGURED));
        providers.put(PROVIDER_B, provider(PROVIDER_B, ProviderConfigurationStatus.CONFIGURED));
        var deployments = new LinkedHashMap<DeploymentId, ModelDeployment>();
        deployments.put(RERANK_A, rerankDeployment(RERANK_A, PROVIDER_A, 10, 8));
        deployments.put(RERANK_B, rerankDeployment(RERANK_B, PROVIDER_B, 20, 8));
        return snapshot(providers, deployments);
    }

    /** 目录里一个 RERANK deployment 都没有。 */
    private static ModelCatalogSnapshot chatOnlyCatalog() {
        var providers = new LinkedHashMap<ProviderId, ModelProvider>();
        providers.put(PROVIDER_A, provider(PROVIDER_A, ProviderConfigurationStatus.CONFIGURED));
        var deployments = new LinkedHashMap<DeploymentId, ModelDeployment>();
        deployments.put(new DeploymentId("chat-a"), new ModelDeployment(
                new DeploymentId("chat-a"), PROVIDER_A, "chat-model",
                new ModelIdentity("chat-a-v1"), Set.of(Capability.CHAT),
                Set.of(ServiceTier.BALANCED), true, 10, true, null));
        return snapshot(providers, deployments);
    }

    /** 声明了 RERANK，但 Provider 缺 secret。 */
    private static ModelCatalogSnapshot unconfiguredCatalog() {
        var providers = new LinkedHashMap<ProviderId, ModelProvider>();
        providers.put(PROVIDER_A, provider(PROVIDER_A, ProviderConfigurationStatus.UNCONFIGURED));
        var deployments = new LinkedHashMap<DeploymentId, ModelDeployment>();
        deployments.put(RERANK_A, rerankDeployment(RERANK_A, PROVIDER_A, 10, 8));
        return snapshot(providers, deployments);
    }

    private static ModelDeployment rerankDeployment(
            DeploymentId id, ProviderId providerId, int priority, int maxDocuments) {
        return new ModelDeployment(
                id, providerId, id.value() + "-model", new ModelIdentity(id.value() + "-v1"),
                Set.of(Capability.RERANK), Set.of(ServiceTier.BALANCED),
                true, priority, true, null, Set.of(), new RerankProfile(maxDocuments));
    }

    private static ModelProvider provider(ProviderId id, ProviderConfigurationStatus status) {
        return new ModelProvider(
                id, "openai-compatible", "供应商 " + id.value(),
                URI.create("http://127.0.0.1:1/v1"), id.value().toUpperCase() + "_API_KEY",
                new DataRegion("CN_TEST"), new QuotaDomain("quota-" + id.value()), status);
    }

    private static ModelCatalogSnapshot snapshot(
            Map<ProviderId, ModelProvider> providers, Map<DeploymentId, ModelDeployment> deployments) {
        return new ModelCatalogSnapshot(
                "test-v1", "a".repeat(64), providers, deployments, runtimePolicy());
    }

    private static AiRuntimePolicy runtimePolicy() {
        return new AiRuntimePolicy(
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(45), 3, 2),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(120), 3, 1),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofMinutes(15), 3, 2),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(20), 3, 2),
                new AiRuntimePolicy.StreamLimits(32, 65_536, 65_536),
                new AiRuntimePolicy.BreakerLimits(
                        20, 5, 50, 50, Duration.ofSeconds(30), 2, Duration.ofSeconds(30)));
    }

    private static CancellationToken cancelled() {
        return new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public CancellationRegistration onCancel(Runnable listener) {
                listener.run();
                return () -> {};
            }
        };
    }

    private record CapturingRecorder(List<CompletedCallDiagnostic> recorded)
            implements DiagnosticRecorder {

        @Override
        public boolean offer(CompletedCallDiagnostic diagnostic) {
            recorded.add(diagnostic);
            return true;
        }

        @Override
        public long droppedCount() {
            return 0;
        }

        @Override
        public long writeFailureCount() {
            return 0;
        }
    }
}
