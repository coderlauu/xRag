package com.app.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.app.ai.model.AssistantMessage;
import com.app.ai.model.Capability;
import com.app.ai.model.CancellationToken;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ProviderId;
import com.app.ai.model.RerankCandidate;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.TokenUsage;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

class ProgrammableFakeProviderTests {

    @Test
    void 相同Chat脚本重复调用产生相同结果和确定计数() {
        var expected = new ProviderChatResult(
                new AssistantMessage("完成", null, List.of()),
                ChatFinishReason.STOP,
                new TokenUsage(2, 1, 3),
                "req-***-42");
        var fake = new ProgrammableFakeProvider(
                ProgrammableFakeProvider.Script.chat((deployment, request, context) -> expected));
        ChatProvider provider = fake;
        var request = new ProviderChatRequest(List.of(), List.of());

        var first = provider.complete(deployment(), request, context(1));
        var second = provider.complete(deployment(), request, context(2));

        assertThat(List.of(first, second)).containsExactly(expected, expected);
        assertThat(fake.invocations())
                .extracting(ProgrammableFakeProvider.Invocation::capability)
                .containsExactly(Capability.CHAT, Capability.CHAT);
        assertThat(fake.releasedCount()).isEqualTo(2);
    }

    @Test
    void 供应商错误在seam处归一且不携带原始正文() {
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.chat(
                (deployment, request, context) -> {
                    throw ProviderFailureException.http(401, null, "req-***-42");
                }));

        var failure = catchThrowableOfType(
                ProviderFailureException.class,
                () -> fake.complete(deployment(), new ProviderChatRequest(List.of(), List.of()), context(1)));

        assertThat(failure.failureType()).isEqualTo(AttemptFailureType.AUTHENTICATION_REJECTED);
        assertThat(failure.httpStatus()).isEqualTo(401);
        assertThat(failure.providerRequestIdMasked()).isEqualTo("req-***-42");
        assertThat(failure.getMessage()).doesNotContain("raw", "secret", "Authorization");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void 流式脚本在订阅时产生确定事件并释放资源() {
        List<ProviderChatEvent> events = List.of(
                new ProviderChatEvent.TextDelta("你"),
                new ProviderChatEvent.TextDelta("好"),
                new ProviderChatEvent.Completed(ChatFinishReason.STOP, null));
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.fromIterable(events)));
        StreamingChatProvider provider = fake;

        var actual = provider.stream(
                        deployment(), new ProviderChatRequest(List.of(), List.of()), context(1))
                .collectList()
                .block();

        assertThat(actual).containsExactlyElementsOf(events);
        assertThat(fake.invocations())
                .extracting(ProgrammableFakeProvider.Invocation::capability)
                .containsExactly(Capability.STREAM_CHAT);
        assertThat(fake.releasedCount()).isEqualTo(1);
    }

    @Test
    void Embedding脚本保留索引和向量结果() {
        var expected = new ProviderEmbeddingResult(
                List.of(
                        new IndexedEmbedding(0, new float[] {1.0f, 2.0f}),
                        new IndexedEmbedding(1, new float[] {3.0f, 4.0f})),
                "req-***-7");
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.embedding(
                (deployment, texts, context) -> expected));
        EmbeddingProvider provider = fake;

        var actual = provider.embed(deployment(), List.of("甲", "乙"), context(1));

        assertThat(actual.embeddings()).hasSize(2);
        assertThat(actual.embeddings().get(1).index()).isEqualTo(1);
        assertThat(actual.embeddings().get(1).vector()).containsExactly(3.0f, 4.0f);
        assertThat(fake.embeddingInputs()).containsExactly(List.of("甲", "乙"));
        assertThat(fake.releasedCount()).isEqualTo(1);
    }

    @Test
    void Rerank脚本返回归一索引而不是供应商DTO() {
        var expected = new ProviderRerankResult(
                List.of(new ProviderRankedItem(1, 0.9), new ProviderRankedItem(0, 0.7)),
                "req-***-9");
        var fake = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.rerank(
                (deployment, request, context) -> expected));
        RerankProvider provider = fake;
        var request = new ProviderRerankRequest(
                "查询",
                List.of(new RerankCandidate("a", "甲"), new RerankCandidate("b", "乙")),
                2);

        var actual = provider.rerank(deployment(), request, context(1));

        assertThat(actual.items()).containsExactlyElementsOf(expected.items());
        assertThat(fake.rerankRequests()).containsExactly(request);
        assertThat(fake.invocations())
                .extracting(ProgrammableFakeProvider.Invocation::capability)
                .containsExactly(Capability.RERANK);
        assertThat(fake.releasedCount()).isEqualTo(1);
    }

    @Test
    void 流式取消与畸形错误都在fake边界留下释放事实() {
        var canceled = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.never()));
        var subscription = canceled.stream(
                        deployment(), new ProviderChatRequest(List.of(), List.of()), context(1))
                .subscribe();

        subscription.dispose();

        assertThat(canceled.cancelledCount()).isEqualTo(1);
        assertThat(canceled.releasedCount()).isEqualTo(1);

        var malformed = new ProgrammableFakeProvider(ProgrammableFakeProvider.Script.stream(
                (deployment, request, context) -> Flux.error(
                        new IllegalArgumentException("raw secret response"))));
        var failure = catchThrowableOfType(
                ProviderFailureException.class,
                () -> malformed.stream(
                                deployment(), new ProviderChatRequest(List.of(), List.of()), context(1))
                        .blockLast());

        assertThat(failure.failureType()).isEqualTo(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
        assertThat(failure.getMessage()).doesNotContain("raw secret response");
        assertThat(malformed.releasedCount()).isEqualTo(1);
    }

    private static ProviderAttemptContext context(int attemptNo) {
        return new ProviderAttemptContext(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                attemptNo,
                () -> Duration.ofSeconds(5),
                CancellationToken.none());
    }

    private static ModelDeployment deployment() {
        return new ModelDeployment(
                new DeploymentId("chat-a"),
                new ProviderId("provider-a"),
                "chat-model",
                new ModelIdentity("chat-model-v1"),
                Set.of(Capability.CHAT),
                Set.of(ServiceTier.BALANCED),
                true,
                10,
                true,
                null);
    }
}
