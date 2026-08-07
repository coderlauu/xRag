package com.app.ai.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.breaker.Resilience4jModelBreakerRegistry;
import com.app.ai.chat.RoutingChatClient;
import com.app.ai.embedding.RoutedEmbeddingClient;
import com.app.ai.model.AiRuntimePolicy;
import com.app.ai.model.CallOptions;
import com.app.ai.model.Capability;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.ChatResult;
import com.app.ai.model.DataRegion;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.EmbeddingCommand;
import com.app.ai.model.EmbeddingProfile;
import com.app.ai.model.EmbeddingResult;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderConfigurationStatus;
import com.app.ai.model.ProviderId;
import com.app.ai.model.QuotaDomain;
import com.app.ai.model.RerankCandidate;
import com.app.ai.model.RerankCommand;
import com.app.ai.model.RerankProfile;
import com.app.ai.model.RerankResult;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.TokenUsage;
import com.app.ai.model.UserMessage;
import com.app.ai.provider.ChatProvider;
import com.app.ai.provider.EmbeddingProvider;
import com.app.ai.provider.IndexedEmbedding;
import com.app.ai.provider.ProgrammableFakeProvider;
import com.app.ai.provider.ProviderChatResult;
import com.app.ai.provider.ProviderEmbeddingResult;
import com.app.ai.provider.ProviderRankedItem;
import com.app.ai.provider.ProviderRerankResult;
import com.app.ai.provider.RerankProvider;
import com.app.ai.rerank.RoutedRerankClient;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * AC-17：完成态诊断是 best effort，**诊断出问题绝不能改变能力端口的返回**。
 *
 * <p>DIA-07（队列满）、DIA-08（存储不可用）、DIA-09（子表回滚）、DIA-17（关闭 flush 上限）
 * 已分别由 {@code AsyncDiagnosticWriterTests} 与 {@code DiagnosticWriterIntegrationTests} 覆盖；
 * 那些用例证明的是 writer 自己的行为。本类补的是另一半：把"诊断彻底坏掉"的 recorder 接到
 * 四个能力端口上，逐一比对结果与健康 recorder 完全一致。
 *
 * <p>这一半单独测有意义——writer 再怎么 best effort，只要某个 client 忘了包 try/catch，
 * 一次诊断异常就会顺着调用栈冒出去变成业务失败。
 */
class DiagnosticBestEffortTests {

    private static final ProviderId PROVIDER = new ProviderId("provider-a");
    private static final DeploymentId CHAT = new DeploymentId("chat-a");
    private static final DeploymentId EMBED = new DeploymentId("embed-a");
    private static final DeploymentId RERANK = new DeploymentId("rerank-a");
    private static final ModelIdentity EMBED_IDENTITY = new ModelIdentity("embed-a-v1");

    @Test
    void chat结果在诊断抛异常时与健康时完全一致() {
        ChatResult healthy = chat(new CountingRecorder());
        ChatResult broken = chat(new ThrowingRecorder());

        assertThat(broken.message().text()).isEqualTo(healthy.message().text());
        assertThat(broken.finishReason()).isEqualTo(healthy.finishReason());
        assertThat(broken.execution().actualExecution())
                .isEqualTo(healthy.execution().actualExecution());
        assertThat(broken.execution().attemptCount()).isEqualTo(healthy.execution().attemptCount());
    }

    @Test
    void embedding结果在诊断抛异常时与健康时完全一致() {
        EmbeddingResult healthy = embed(new CountingRecorder());
        EmbeddingResult broken = embed(new ThrowingRecorder());

        assertThat(broken.vectors()).hasSameSizeAs(healthy.vectors());
        assertThat(broken.vectors().get(0)).containsExactly(healthy.vectors().get(0));
        assertThat(broken.dimensions()).isEqualTo(healthy.dimensions());
        assertThat(broken.modelIdentity()).isEqualTo(healthy.modelIdentity());
    }

    @Test
    void rerank结果在诊断抛异常时与健康时完全一致() {
        RerankResult healthy = rerank(new CountingRecorder());
        RerankResult broken = rerank(new ThrowingRecorder());

        assertThat(broken.rerankApplied()).isEqualTo(healthy.rerankApplied());
        assertThat(broken.items()).extracting(item -> item.id())
                .isEqualTo(healthy.items().stream().map(item -> item.id()).toList());
        assertThat(broken.execution().actualExecution())
                .isEqualTo(healthy.execution().actualExecution());
    }

    @Test
    void rerank的noop降级在诊断抛异常时同样不受影响() {
        // 降级路径也走 recordBestEffort，容易在"反正是降级"的心态下漏掉 try/catch。
        RerankResult healthy = rerankNoop(new CountingRecorder());
        RerankResult broken = rerankNoop(new ThrowingRecorder());

        assertThat(broken.rerankApplied()).isFalse();
        assertThat(broken.degradationReason()).isEqualTo(healthy.degradationReason());
        assertThat(broken.items()).extracting(item -> item.id())
                .isEqualTo(healthy.items().stream().map(item -> item.id()).toList());
    }

    @Test
    void 诊断全部被丢弃时能力结果仍然正常() {
        // 队列满对应的是 offer 返回 false（不抛），与抛异常是两条不同的分支。
        var dropping = new DroppingRecorder();

        ChatResult result = chat(dropping);

        assertThat(result.message().text()).isEqualTo("好的");
        assertThat(dropping.dropped).isPositive();
    }

    private static ChatResult chat(DiagnosticRecorder recorder) {
        var provider = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> new ProviderChatResult(
                        new com.app.ai.model.AssistantMessage("好的", null, List.of()),
                        ChatFinishReason.STOP,
                        new TokenUsage(2, 1, 3),
                        "req-***-1")));
        var catalog = catalog();
        return new RoutingChatClient(
                catalog,
                Map.<ProviderId, ChatProvider>of(PROVIDER, provider),
                registry(),
                recorder)
                .complete(new ChatCommand(
                        List.of(new UserMessage("你好")), List.of(), ServiceTier.BALANCED,
                        CallOptions.defaults()));
    }

    private static EmbeddingResult embed(DiagnosticRecorder recorder) {
        var provider = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.embedding(
                (deployment, texts, context) -> new ProviderEmbeddingResult(
                        List.of(new IndexedEmbedding(0, new float[] {0.1f, 0.2f})), null)));
        return new RoutedEmbeddingClient(
                catalog(),
                Map.<ProviderId, EmbeddingProvider>of(PROVIDER, provider),
                registry(),
                recorder)
                .embed(new EmbeddingCommand(EMBED_IDENTITY, List.of("正文"), CallOptions.defaults()));
    }

    private static RerankResult rerank(DiagnosticRecorder recorder) {
        var provider = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.rerank(
                (deployment, request, context) -> new ProviderRerankResult(
                        List.of(new ProviderRankedItem(1, 0.9), new ProviderRankedItem(0, 0.4)),
                        null)));
        return new RoutedRerankClient(
                catalog(),
                Map.<ProviderId, RerankProvider>of(PROVIDER, provider),
                registry(),
                recorder)
                .rerank(rerankCommand());
    }

    private static RerankResult rerankNoop(DiagnosticRecorder recorder) {
        // 目录里没有 RERANK deployment，走 CAPABILITY_NOT_DECLARED 降级。
        return new RoutedRerankClient(
                chatOnlyCatalog(), Map.of(), registry(), recorder)
                .rerank(rerankCommand());
    }

    private static RerankCommand rerankCommand() {
        return new RerankCommand(
                "查询",
                List.of(new RerankCandidate("doc-0", "甲"), new RerankCandidate("doc-1", "乙")),
                2,
                ServiceTier.BALANCED,
                CallOptions.defaults());
    }

    private static Resilience4jModelBreakerRegistry registry() {
        return new Resilience4jModelBreakerRegistry(
                runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
    }

    private static ModelCatalogSnapshot catalog() {
        var providers = providerMap();
        var deployments = new LinkedHashMap<DeploymentId, ModelDeployment>();
        deployments.put(CHAT, new ModelDeployment(
                CHAT, PROVIDER, "chat-model", new ModelIdentity("chat-a-v1"),
                Set.of(Capability.CHAT), Set.of(ServiceTier.BALANCED), true, 10, true, null));
        deployments.put(EMBED, new ModelDeployment(
                EMBED, PROVIDER, "embed-model", EMBED_IDENTITY,
                Set.of(Capability.EMBEDDING), Set.of(), false, 10, true,
                new EmbeddingProfile(false, null, 2, 10, 8192, "RAW_UTF8_V1")));
        deployments.put(RERANK, new ModelDeployment(
                RERANK, PROVIDER, "rerank-model", new ModelIdentity("rerank-a-v1"),
                Set.of(Capability.RERANK), Set.of(ServiceTier.BALANCED), true, 10, true,
                null, Set.of(), new RerankProfile(50)));
        return new ModelCatalogSnapshot(
                "test-v1", "a".repeat(64), providers, deployments, runtimePolicy());
    }

    private static ModelCatalogSnapshot chatOnlyCatalog() {
        var deployments = new LinkedHashMap<DeploymentId, ModelDeployment>();
        deployments.put(CHAT, new ModelDeployment(
                CHAT, PROVIDER, "chat-model", new ModelIdentity("chat-a-v1"),
                Set.of(Capability.CHAT), Set.of(ServiceTier.BALANCED), true, 10, true, null));
        return new ModelCatalogSnapshot(
                "test-v1", "a".repeat(64), providerMap(), deployments, runtimePolicy());
    }

    private static Map<ProviderId, ModelProvider> providerMap() {
        var providers = new LinkedHashMap<ProviderId, ModelProvider>();
        providers.put(PROVIDER, new ModelProvider(
                PROVIDER, "openai-compatible", "供应商甲",
                URI.create("http://127.0.0.1:1/v1"), "PROVIDER_A_API_KEY",
                new DataRegion("CN_TEST"), new QuotaDomain("quota-a"),
                ProviderConfigurationStatus.CONFIGURED));
        return providers;
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

    /** 每个方法都抛：模拟 writer 组件本身坏掉，而不只是写库失败。 */
    private static final class ThrowingRecorder implements DiagnosticRecorder {

        @Override
        public boolean offer(CompletedCallDiagnostic diagnostic) {
            throw new IllegalStateException("诊断组件不可用");
        }

        @Override
        public long droppedCount() {
            throw new IllegalStateException("诊断组件不可用");
        }

        @Override
        public long writeFailureCount() {
            throw new IllegalStateException("诊断组件不可用");
        }
    }

    /** offer 返回 false：队列满的真实形状，不抛异常。 */
    private static final class DroppingRecorder implements DiagnosticRecorder {

        private int dropped;

        @Override
        public boolean offer(CompletedCallDiagnostic diagnostic) {
            dropped++;
            return false;
        }

        @Override
        public long droppedCount() {
            return dropped;
        }

        @Override
        public long writeFailureCount() {
            return 0;
        }
    }

    private static final class CountingRecorder implements DiagnosticRecorder {

        @Override
        public boolean offer(CompletedCallDiagnostic diagnostic) {
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
