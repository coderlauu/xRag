package com.app.ai.embedding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.app.ai.breaker.Resilience4jModelBreakerRegistry;
import com.app.ai.diagnostic.AttemptKind;
import com.app.ai.diagnostic.AttemptOutcome;
import com.app.ai.diagnostic.CallOutcome;
import com.app.ai.diagnostic.CompletedCallDiagnostic;
import com.app.ai.diagnostic.DiagnosticRecorder;
import com.app.ai.diagnostic.RouteDecision;
import com.app.ai.diagnostic.RouteRejectionReason;
import com.app.ai.model.AiRuntimePolicy;
import com.app.ai.model.CallOptions;
import com.app.ai.model.CancellationRegistration;
import com.app.ai.model.CancellationToken;
import com.app.ai.model.Capability;
import com.app.ai.model.DataRegion;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.EmbeddingClient;
import com.app.ai.model.EmbeddingCommand;
import com.app.ai.model.EmbeddingProfile;
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
import com.app.ai.model.TerminationReason;
import com.app.ai.provider.EmbeddingProvider;
import com.app.ai.provider.IndexedEmbedding;
import com.app.ai.provider.ProviderEmbeddingResult;
import com.app.ai.provider.ProviderFailureException;
import com.app.ai.routing.RoutePlanner;
import com.app.ai.runtime.NanoTicker;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RoutedEmbeddingClientTests {

    private static final ProviderId PROVIDER_A = new ProviderId("provider-a");
    private static final ProviderId PROVIDER_B = new ProviderId("provider-b");
    private static final DeploymentId EMBED_A = new DeploymentId("embed-a");
    private static final DeploymentId EMBED_B = new DeploymentId("embed-b");
    private static final DeploymentId EMBED_C = new DeploymentId("embed-c");
    private static final ModelIdentity SHARED_IDENTITY = new ModelIdentity("kb-embedding-v1");
    private static final ModelIdentity FOREIGN_IDENTITY = new ModelIdentity("other-embedding-v1");
    private static final ModelIdentity LEGACY_IDENTITY = new ModelIdentity("legacy-embedding-v1");
    private static final int DIMENSIONS = 4;

    @Test
    void emb05和18_按最小批量上限切分且顺序与唯一贡献者正确() {
        var catalog = catalog();
        var recorded = new ArrayList<List<String>>();
        EmbeddingProvider provider = (deployment, texts, context) -> {
            context.lifecycle().dispatched();
            recorded.add(List.copyOf(texts));
            return echo(texts);
        };
        var diagnostics = new CapturingDiagnosticRecorder();
        EmbeddingClient client = client(catalog, provider, diagnostics);
        List<String> texts = inputs(23);

        var result = client.embed(new EmbeddingCommand(
                SHARED_IDENTITY, texts, CallOptions.defaults()));

        assertThat(result.modelIdentity()).isEqualTo(SHARED_IDENTITY);
        assertThat(result.dimensions()).isEqualTo(DIMENSIONS);
        assertThat(result.vectors()).hasSize(23);
        for (int index = 0; index < texts.size(); index++) {
            assertThat(result.vectors().get(index)[0])
                    .as("vector %s must stay aligned with its input", index)
                    .isEqualTo((float) index);
        }
        assertThat(recorded).extracting(List::size).containsExactly(10, 10, 3);
        assertThat(recorded.get(1)).first().isEqualTo(texts.get(10));
        assertThat(recorded.get(2)).first().isEqualTo(texts.get(20));

        assertThat(result.execution().mixedExecution()).isFalse();
        assertThat(result.execution().contributingDeployments())
                .extracting(target -> target.deploymentId())
                .containsExactly(EMBED_A);
        assertThat(result.execution().attemptCount()).isEqualTo(3);
        assertThat(result.execution().batches())
                .extracting(batch -> batch.workUnitIndex())
                .containsExactly(0, 1, 2);
        assertThat(result.execution().batches()).allSatisfy(batch ->
                assertThat(batch.actualExecution().deploymentId()).isEqualTo(EMBED_A));

        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.SUCCEEDED);
            assertThat(diagnostic.capability()).isEqualTo(Capability.EMBEDDING);
            assertThat(diagnostic.requiredModelIdentity()).isEqualTo(SHARED_IDENTITY);
            assertThat(diagnostic.attempts()).hasSize(3);
            assertThat(diagnostic.attempts()).allSatisfy(attempt ->
                    assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.SUCCEEDED));
            assertThat(diagnostic.attempts()).extracting(attempt -> attempt.workUnitIndex())
                    .containsExactly(0, 1, 2);
            assertThat(diagnostic.attempts()).extracting(
                            attempt -> attempt.inputFromInclusive(),
                            attempt -> attempt.inputToExclusive())
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple(0, 10),
                            org.assertj.core.groups.Tuple.tuple(10, 20),
                            org.assertj.core.groups.Tuple.tuple(20, 23));
            assertThat(diagnostic.executionSummary().contributingDeploymentCount()).isEqualTo(1);
            assertThat(diagnostic.executionSummary().mixedExecution()).isFalse();
            assertThat(diagnostic.usage().embeddingInputCount()).isEqualTo(23);
            assertThat(diagnostic.usage().embeddingBatchCount()).isEqualTo(3);
            assertThat(diagnostic.usage().embeddingDimensions()).isEqualTo(DIMENSIONS);
        });
    }

    @Test
    void emb06_Provider乱序返回时按index还原为输入顺序() {
        var catalog = catalog();
        EmbeddingProvider provider = (deployment, texts, context) -> {
            context.lifecycle().dispatched();
            List<IndexedEmbedding> shuffled = new ArrayList<>(echo(texts).embeddings());
            java.util.Collections.reverse(shuffled);
            return new ProviderEmbeddingResult(shuffled, "req_****x");
        };

        var result = client(catalog, provider, new CapturingDiagnosticRecorder())
                .embed(new EmbeddingCommand(SHARED_IDENTITY, inputs(3), CallOptions.defaults()));

        assertThat(result.vectors()).extracting(vector -> vector[0])
                .containsExactly(0f, 1f, 2f);
    }

    @Test
    void emb07_重复index按协议失败() {
        assertMalformedBatchRejected(batchSize -> {
            var embeddings = new ArrayList<IndexedEmbedding>();
            for (int index = 0; index < batchSize; index++) {
                embeddings.add(new IndexedEmbedding(0, new float[DIMENSIONS]));
            }
            return new ProviderEmbeddingResult(embeddings, "req_****x");
        });
    }

    @Test
    void emb07_越界index按协议失败() {
        assertMalformedBatchRejected(batchSize -> {
            var embeddings = new ArrayList<IndexedEmbedding>();
            for (int index = 0; index < batchSize; index++) {
                embeddings.add(new IndexedEmbedding(index + batchSize, new float[DIMENSIONS]));
            }
            return new ProviderEmbeddingResult(embeddings, "req_****x");
        });
    }

    @Test
    void emb08_返回条数不足按协议失败() {
        assertMalformedBatchRejected(batchSize -> new ProviderEmbeddingResult(
                List.of(new IndexedEmbedding(0, new float[DIMENSIONS])), "req_****x"));
    }

    @Test
    void emb09_维度不符按协议失败() {
        assertMalformedBatchRejected(batchSize -> {
            var embeddings = new ArrayList<IndexedEmbedding>();
            for (int index = 0; index < batchSize; index++) {
                embeddings.add(new IndexedEmbedding(index, new float[DIMENSIONS + 1]));
            }
            return new ProviderEmbeddingResult(embeddings, "req_****x");
        });
    }

    @Test
    void emb10_向量含NaN或无穷按协议失败() {
        assertMalformedBatchRejected(batchSize -> {
            var embeddings = new ArrayList<IndexedEmbedding>();
            for (int index = 0; index < batchSize; index++) {
                float[] vector = new float[DIMENSIONS];
                vector[1] = index == 0 ? Float.NaN : Float.POSITIVE_INFINITY;
                embeddings.add(new IndexedEmbedding(index, vector));
            }
            return new ProviderEmbeddingResult(embeddings, "req_****x");
        });
    }

    /**
     * Asserts a malformed provider batch is classified as a protocol failure that settles the
     * breaker and may switch to the equivalent candidate (EMB-04/EMB-07), and that when no
     * candidate returns a valid batch the whole call fails without partial vectors.
     */
    private static void assertMalformedBatchRejected(IntFunction<ProviderEmbeddingResult> malformed) {
        var catalog = catalog();
        EmbeddingProvider failoverProvider = (deployment, texts, context) -> {
            context.lifecycle().dispatched();
            return deployment.id().equals(EMBED_A) ? malformed.apply(texts.size()) : echo(texts);
        };
        var failoverDiagnostics = new CapturingDiagnosticRecorder();
        var failoverBreakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        var recovered = client(catalog, failoverProvider, failoverDiagnostics, failoverBreakers)
                .embed(new EmbeddingCommand(SHARED_IDENTITY, inputs(3), CallOptions.defaults()));

        assertThat(recovered.vectors()).extracting(vector -> vector[0]).containsExactly(0f, 1f, 2f);
        assertThat(recovered.execution().contributingDeployments())
                .extracting(target -> target.deploymentId())
                .containsExactly(EMBED_B);
        assertThat(failoverBreakers.snapshot(target(EMBED_A), Capability.EMBEDDING).failedCalls())
                .isEqualTo(1);
        assertThat(failoverDiagnostics.calls).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.attempts()).first().satisfies(attempt -> {
                    assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.FAILED);
                    assertThat(attempt.normalizedErrorType())
                            .isEqualTo(ModelErrorType.PROTOCOL_CONTRACT);
                    assertThat(attempt.failurePhase())
                            .isEqualTo(FailurePhase.PROVIDER_RESPONSE_VALIDATION);
                }));

        EmbeddingProvider brokenProvider = (deployment, texts, context) -> {
            context.lifecycle().dispatched();
            return malformed.apply(texts.size());
        };
        var exhaustedDiagnostics = new CapturingDiagnosticRecorder();
        EmbeddingClient exhausted = client(catalog, brokenProvider, exhaustedDiagnostics);

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> exhausted.embed(new EmbeddingCommand(
                        SHARED_IDENTITY, inputs(3), CallOptions.defaults())))
                .satisfies(failure -> {
                    assertThat(failure.terminalCause().errorType())
                            .isEqualTo(ModelErrorType.PROTOCOL_CONTRACT);
                    assertThat(failure.terminalCause().terminationReason())
                            .isEqualTo(TerminationReason.ALL_CANDIDATES_EXHAUSTED);
                });
        assertThat(exhaustedDiagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.FAILED);
            assertThat(diagnostic.attempts()).hasSize(2);
            assertThat(diagnostic.executionSummary().contributingDeploymentCount()).isZero();
        });
    }

    @Test
    void emb11和17_中间批次切换等价Deployment时不重算且贡献者按首次贡献去重() {
        var catalog = catalog();
        var served = new ArrayList<String>();
        EmbeddingProvider provider = (deployment, texts, context) -> {
            context.lifecycle().dispatched();
            served.add(deployment.id().value() + ":" + texts.get(0));
            if (deployment.id().equals(EMBED_A) && texts.get(0).equals("text-10")) {
                throw ProviderFailureException.http(503, null, "req_****a");
            }
            return echo(texts);
        };
        var diagnostics = new CapturingDiagnosticRecorder();
        List<String> texts = inputs(23);

        var result = client(catalog, provider, diagnostics)
                .embed(new EmbeddingCommand(SHARED_IDENTITY, texts, CallOptions.defaults()));

        assertThat(result.vectors()).hasSize(23);
        for (int index = 0; index < texts.size(); index++) {
            assertThat(result.vectors().get(index)[0]).isEqualTo((float) index);
        }
        assertThat(served).containsExactly(
                "embed-a:text-0",
                "embed-a:text-10",
                "embed-a:text-10",
                "embed-b:text-10",
                "embed-b:text-20");
        assertThat(result.execution().mixedExecution()).isTrue();
        assertThat(result.execution().contributingDeployments())
                .extracting(target -> target.deploymentId())
                .containsExactly(EMBED_A, EMBED_B);
        assertThat(result.execution().batches())
                .extracting(batch -> batch.actualExecution().deploymentId())
                .containsExactly(EMBED_A, EMBED_B, EMBED_B);
        assertThat(result.execution().retryCount()).isEqualTo(1);
        assertThat(result.execution().failoverCount()).isEqualTo(1);

        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.SUCCEEDED);
            assertThat(diagnostic.attempts()).hasSize(5);
            assertThat(diagnostic.executionSummary().mixedExecution()).isTrue();
            assertThat(diagnostic.executionSummary().contributingDeploymentCount()).isEqualTo(2);
            assertThat(diagnostic.executionSummary().actualExecution()).isNull();
            assertThat(diagnostic.embeddingBatches())
                    .extracting(batch -> batch.actualExecution().deploymentId())
                    .containsExactly(EMBED_A, EMBED_B, EMBED_B);
        });
    }

    @Test
    void emb23_后续批次最终失败时不返回部分向量且调用级贡献者为零() {
        var catalog = catalog();
        EmbeddingProvider provider = (deployment, texts, context) -> {
            context.lifecycle().dispatched();
            if (texts.get(0).equals("text-10")) {
                throw ProviderFailureException.http(503, null, "req_****x");
            }
            return echo(texts);
        };
        var diagnostics = new CapturingDiagnosticRecorder();
        EmbeddingClient client = client(catalog, provider, diagnostics);

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client.embed(new EmbeddingCommand(
                        SHARED_IDENTITY, inputs(23), CallOptions.defaults())))
                .satisfies(failure -> assertThat(failure.terminalCause().errorType())
                        .isEqualTo(ModelErrorType.SERVER_ERROR));

        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.FAILED);
            assertThat(diagnostic.executionSummary().contributingDeploymentCount()).isZero();
            assertThat(diagnostic.executionSummary().mixedExecution()).isFalse();
            assertThat(diagnostic.executionSummary().actualExecution()).isNull();
            assertThat(diagnostic.attempts())
                    .filteredOn(attempt -> attempt.outcome() == AttemptOutcome.SUCCEEDED)
                    .singleElement()
                    .satisfies(attempt -> assertThat(attempt.workUnitIndex()).isZero());
        });
    }

    @Test
    void emb24_完整结果返回前取消时不返回部分向量且已完成批只留作attempt() {
        var catalog = catalog();
        var token = new MutableCancellationToken();
        EmbeddingProvider provider = (deployment, texts, context) -> {
            context.lifecycle().dispatched();
            ProviderEmbeddingResult result = echo(texts);
            token.cancel();
            return result;
        };
        var diagnostics = new CapturingDiagnosticRecorder();
        EmbeddingClient client = client(catalog, provider, diagnostics);

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client.embed(new EmbeddingCommand(
                        SHARED_IDENTITY,
                        inputs(23),
                        new CallOptions(null, null, token, Set.of()))))
                .satisfies(failure -> {
                    assertThat(failure.terminalCause().errorType())
                            .isEqualTo(ModelErrorType.CANCELED);
                    assertThat(failure.terminalCause().terminationReason())
                            .isEqualTo(TerminationReason.CALLER_CANCELED);
                });

        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.CANCELED);
            assertThat(diagnostic.executionSummary().contributingDeploymentCount()).isZero();
            assertThat(diagnostic.executionSummary().actualExecution()).isNull();
            assertThat(diagnostic.attempts()).singleElement().satisfies(attempt -> {
                assertThat(attempt.outcome()).isEqualTo(AttemptOutcome.SUCCEEDED);
                assertThat(attempt.workUnitIndex()).isZero();
            });
        });
    }

    @Test
    void emb02和03_只在同identity候选间恢复且同维不同identity按身份不符过滤() {
        var catalog = catalogWithForeignIdentity();
        var served = new ArrayList<DeploymentId>();
        EmbeddingProvider provider = (deployment, texts, context) -> {
            context.lifecycle().dispatched();
            served.add(deployment.id());
            throw ProviderFailureException.http(503, null, "req_****x");
        };
        var diagnostics = new CapturingDiagnosticRecorder();
        EmbeddingClient client = client(catalog, provider, diagnostics);

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client.embed(new EmbeddingCommand(
                        SHARED_IDENTITY, inputs(3), CallOptions.defaults())));

        assertThat(served).doesNotContain(EMBED_C).containsOnly(EMBED_A, EMBED_B);
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.candidates())
                    .filteredOn(candidate -> candidate.target().deploymentId().equals(EMBED_C))
                    .singleElement()
                    .satisfies(candidate -> {
                        assertThat(candidate.decision()).isEqualTo(RouteDecision.FILTERED);
                        assertThat(candidate.rejectionReason())
                                .isEqualTo(RouteRejectionReason.MODEL_IDENTITY_MISMATCH);
                    });
            assertThat(diagnostic.attempts())
                    .extracting(attempt -> attempt.target().modelIdentity())
                    .containsOnly(SHARED_IDENTITY);
        });
    }

    @Test
    void emb12_identity没有合格候选时明确失败且不回退默认模型() {
        var catalog = catalog();
        var invoked = new AtomicBoolean();
        EmbeddingProvider provider = (deployment, texts, context) -> {
            invoked.set(true);
            context.lifecycle().dispatched();
            return echo(texts);
        };
        var diagnostics = new CapturingDiagnosticRecorder();
        EmbeddingClient client = client(catalog, provider, diagnostics);

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client.embed(new EmbeddingCommand(
                        new ModelIdentity("unconfigured-identity"),
                        inputs(3),
                        CallOptions.defaults())))
                .satisfies(failure -> {
                    assertThat(failure.terminalCause().errorType())
                            .isEqualTo(ModelErrorType.NO_ELIGIBLE_MODEL);
                    assertThat(failure.terminalCause().terminationReason())
                            .isEqualTo(TerminationReason.NO_ELIGIBLE_MODEL);
                    assertThat(failure.execution().attemptCount()).isZero();
                });
        assertThat(invoked).isFalse();
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.attempts()).isEmpty());
    }

    @Test
    void emb19_legacyIdentity只有唯一deployment时不形成故障转移() {
        var catalog = catalogWithLegacyIdentity();
        var served = new ArrayList<DeploymentId>();
        EmbeddingProvider provider = (deployment, texts, context) -> {
            context.lifecycle().dispatched();
            served.add(deployment.id());
            throw ProviderFailureException.http(503, null, "req_****x");
        };
        var diagnostics = new CapturingDiagnosticRecorder();
        EmbeddingClient client = client(catalog, provider, diagnostics);

        assertThatExceptionOfType(ModelCallException.class)
                .isThrownBy(() -> client.embed(new EmbeddingCommand(
                        LEGACY_IDENTITY, inputs(3), CallOptions.defaults())));

        assertThat(served).containsOnly(EMBED_A);
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic ->
                assertThat(diagnostic.attempts())
                        .extracting(attempt -> attempt.attemptKind())
                        .doesNotContain(AttemptKind.FAILOVER));
    }

    @Test
    void 空输入返回空结果且不创建Provider调用() {
        var catalog = catalog();
        var invoked = new AtomicBoolean();
        EmbeddingProvider provider = (deployment, texts, context) -> {
            invoked.set(true);
            context.lifecycle().dispatched();
            return echo(texts);
        };
        var diagnostics = new CapturingDiagnosticRecorder();

        var result = client(catalog, provider, diagnostics)
                .embed(new EmbeddingCommand(SHARED_IDENTITY, List.of(), CallOptions.defaults()));

        assertThat(result.vectors()).isEmpty();
        assertThat(result.dimensions()).isEqualTo(DIMENSIONS);
        assertThat(result.execution().mixedExecution()).isFalse();
        assertThat(result.execution().contributingDeployments()).isEmpty();
        assertThat(invoked).isFalse();
        assertThat(diagnostics.calls).singleElement().satisfies(diagnostic -> {
            assertThat(diagnostic.outcome()).isEqualTo(CallOutcome.SUCCEEDED);
            assertThat(diagnostic.attempts()).isEmpty();
            assertThat(diagnostic.usage().embeddingInputCount()).isZero();
        });
    }

    @Test
    void emb14_Provider调用发生在Spring事务之外() {
        var catalog = catalog();
        var transactionActive = new AtomicBoolean(true);
        EmbeddingProvider provider = (deployment, texts, context) -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            context.lifecycle().dispatched();
            return echo(texts);
        };

        client(catalog, provider, new CapturingDiagnosticRecorder())
                .embed(new EmbeddingCommand(SHARED_IDENTITY, inputs(3), CallOptions.defaults()));

        assertThat(transactionActive).isFalse();
    }

    private static ExecutionTarget target(DeploymentId deploymentId) {
        ModelDeployment deployment = catalog().deployments().get(deploymentId);
        return new ExecutionTarget(
                deployment.providerId(), deployment.id(), deployment.modelIdentity());
    }

    private static List<String> inputs(int count) {
        return IntStream.range(0, count).mapToObj(index -> "text-" + index).toList();
    }

    /** Encodes each input's ordinal into the vector so misordered results are detectable. */
    private static ProviderEmbeddingResult echo(List<String> texts) {
        List<IndexedEmbedding> embeddings = new ArrayList<>(texts.size());
        for (int index = 0; index < texts.size(); index++) {
            float ordinal = Float.parseFloat(texts.get(index).substring("text-".length()));
            float[] vector = new float[DIMENSIONS];
            vector[0] = ordinal;
            embeddings.add(new IndexedEmbedding(index, vector));
        }
        return new ProviderEmbeddingResult(embeddings, "req_****x");
    }

    private static EmbeddingClient client(
            ModelCatalogSnapshot catalog,
            EmbeddingProvider provider,
            DiagnosticRecorder diagnostics) {
        var breakers = new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
        return client(catalog, provider, diagnostics, breakers);
    }

    /** Uses a recording sleeper and fixed jitter so retry backoff never really blocks a test. */
    private static EmbeddingClient client(
            ModelCatalogSnapshot catalog,
            EmbeddingProvider provider,
            DiagnosticRecorder diagnostics,
            Resilience4jModelBreakerRegistry breakers) {
        return new RoutedEmbeddingClient(
                catalog,
                Map.of(PROVIDER_A, provider, PROVIDER_B, provider),
                breakers,
                diagnostics,
                new RoutePlanner(),
                Clock.systemUTC(),
                NanoTicker.system(),
                upperBound -> Duration.ofMillis(5),
                ignored -> { },
                Duration.ofMillis(1));
    }

    private static ModelCatalogSnapshot catalog() {
        var providers = new LinkedHashMap<ProviderId, ModelProvider>();
        providers.put(PROVIDER_A, provider(PROVIDER_A, "quota-a"));
        providers.put(PROVIDER_B, provider(PROVIDER_B, "quota-b"));
        var deployments = new LinkedHashMap<DeploymentId, ModelDeployment>();
        deployments.put(EMBED_A, deployment(EMBED_A, PROVIDER_A, SHARED_IDENTITY, true, 10, 10));
        deployments.put(EMBED_B, deployment(EMBED_B, PROVIDER_B, SHARED_IDENTITY, false, 20, 16));
        return new ModelCatalogSnapshot(
                "test-v1", "a".repeat(64), providers, deployments, runtimePolicy());
    }

    /** Adds a same-dimension deployment under a different identity; it must never be a candidate. */
    private static ModelCatalogSnapshot catalogWithForeignIdentity() {
        var base = catalog();
        var deployments = new LinkedHashMap<>(base.deployments());
        deployments.put(EMBED_C, deployment(EMBED_C, PROVIDER_B, FOREIGN_IDENTITY, false, 30, 10));
        return new ModelCatalogSnapshot(
                base.policyVersion(),
                base.configFingerprint(),
                base.providers(),
                deployments,
                base.runtimePolicy());
    }

    private static ModelCatalogSnapshot catalogWithLegacyIdentity() {
        var providers = new LinkedHashMap<ProviderId, ModelProvider>();
        providers.put(PROVIDER_A, provider(PROVIDER_A, "quota-a"));
        providers.put(PROVIDER_B, provider(PROVIDER_B, "quota-b"));
        var deployments = new LinkedHashMap<DeploymentId, ModelDeployment>();
        deployments.put(EMBED_A, legacyDeployment(EMBED_A, PROVIDER_A, LEGACY_IDENTITY));
        deployments.put(EMBED_B, deployment(EMBED_B, PROVIDER_B, SHARED_IDENTITY, false, 20, 16));
        return new ModelCatalogSnapshot(
                "test-v1", "a".repeat(64), providers, deployments, runtimePolicy());
    }

    private static ModelDeployment legacyDeployment(
            DeploymentId id, ProviderId providerId, ModelIdentity identity) {
        return new ModelDeployment(
                id,
                providerId,
                id.value() + "-model",
                identity,
                Set.of(Capability.EMBEDDING),
                Set.of(),
                true,
                10,
                true,
                new EmbeddingProfile(true, null, DIMENSIONS, 10, 512, null));
    }

    private static ModelProvider provider(ProviderId id, String quota) {
        return new ModelProvider(
                id,
                "openai-compatible",
                id.value(),
                URI.create("http://127.0.0.1:1/v1"),
                "TEST_API_KEY",
                new DataRegion("CN_TEST"),
                new QuotaDomain(quota),
                ProviderConfigurationStatus.CONFIGURED);
    }

    private static ModelDeployment deployment(
            DeploymentId id,
            ProviderId providerId,
            ModelIdentity identity,
            boolean defaultCandidate,
            int priority,
            int maxBatchItems) {
        return new ModelDeployment(
                id,
                providerId,
                id.value() + "-model",
                identity,
                Set.of(Capability.EMBEDDING),
                Set.of(),
                defaultCandidate,
                priority,
                true,
                new EmbeddingProfile(
                        false, "artifact-1", DIMENSIONS, maxBatchItems, 512, "preprocess-1"));
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

    private static final class MutableCancellationToken implements CancellationToken {

        private final AtomicBoolean canceled = new AtomicBoolean();

        @Override
        public boolean isCancelled() {
            return canceled.get();
        }

        @Override
        public CancellationRegistration onCancel(Runnable listener) {
            return () -> { };
        }

        void cancel() {
            canceled.set(true);
        }
    }

    private static final class CapturingDiagnosticRecorder implements DiagnosticRecorder {

        private final List<CompletedCallDiagnostic> calls = new ArrayList<>();

        @Override
        public boolean offer(CompletedCallDiagnostic diagnostic) {
            calls.add(diagnostic);
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
