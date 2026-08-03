package com.app.ai.provider;

import com.app.ai.model.Capability;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ModelDeployment;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;

public final class ProgrammableFakeProvider
        implements ChatProvider, StreamingChatProvider, EmbeddingProvider, RerankProvider {

    private final Script script;
    private final List<Invocation> invocations = new CopyOnWriteArrayList<>();
    private final List<ProviderChatRequest> chatRequests = new CopyOnWriteArrayList<>();
    private final List<List<String>> embeddingInputs = new CopyOnWriteArrayList<>();
    private final List<ProviderRerankRequest> rerankRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger releasedCount = new AtomicInteger();
    private final AtomicInteger cancelledCount = new AtomicInteger();

    public ProgrammableFakeProvider(Script script) {
        this.script = Objects.requireNonNull(script);
    }

    @Override
    public ProviderChatResult complete(
            ModelDeployment deployment,
            ProviderChatRequest request,
            ProviderAttemptContext context) {
        Objects.requireNonNull(deployment);
        Objects.requireNonNull(request);
        Objects.requireNonNull(context);
        invocations.add(new Invocation(Capability.CHAT, deployment.id(), context.attemptNo()));
        chatRequests.add(request);
        try {
            rejectIfCancelled(context);
            context.lifecycle().dispatched();
            return script.chat().complete(deployment, request, context);
        } catch (ProviderFailureException failure) {
            throw failure;
        } catch (RuntimeException unexpected) {
            throw ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
        } finally {
            releasedCount.incrementAndGet();
        }
    }

    @Override
    public Flux<ProviderChatEvent> stream(
            ModelDeployment deployment,
            ProviderChatRequest request,
            ProviderAttemptContext context) {
        Objects.requireNonNull(deployment);
        Objects.requireNonNull(request);
        Objects.requireNonNull(context);
        return Flux.defer(() -> {
                    invocations.add(new Invocation(Capability.STREAM_CHAT, deployment.id(), context.attemptNo()));
                    chatRequests.add(request);
                    try {
                        rejectIfCancelled(context);
                        context.lifecycle().dispatched();
                        context.lifecycle().connected();
                        context.lifecycle().responseHeadersReceived();
                        return script.stream().stream(deployment, request, context)
                                .onErrorMap(this::normalizedFailure);
                    } catch (RuntimeException failure) {
                        return Flux.error(normalizedFailure(failure));
                    }
                })
                .doOnCancel(cancelledCount::incrementAndGet)
                .doFinally(signal -> releasedCount.incrementAndGet());
    }

    @Override
    public ProviderEmbeddingResult embed(
            ModelDeployment deployment,
            List<String> texts,
            ProviderAttemptContext context) {
        Objects.requireNonNull(deployment);
        List<String> inputCopy = List.copyOf(texts);
        Objects.requireNonNull(context);
        invocations.add(new Invocation(Capability.EMBEDDING, deployment.id(), context.attemptNo()));
        embeddingInputs.add(inputCopy);
        try {
            rejectIfCancelled(context);
            context.lifecycle().dispatched();
            return script.embedding().embed(deployment, inputCopy, context);
        } catch (ProviderFailureException failure) {
            throw failure;
        } catch (RuntimeException unexpected) {
            throw ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
        } finally {
            releasedCount.incrementAndGet();
        }
    }

    @Override
    public ProviderRerankResult rerank(
            ModelDeployment deployment,
            ProviderRerankRequest request,
            ProviderAttemptContext context) {
        Objects.requireNonNull(deployment);
        Objects.requireNonNull(request);
        Objects.requireNonNull(context);
        invocations.add(new Invocation(Capability.RERANK, deployment.id(), context.attemptNo()));
        rerankRequests.add(request);
        try {
            rejectIfCancelled(context);
            context.lifecycle().dispatched();
            return script.rerank().rerank(deployment, request, context);
        } catch (ProviderFailureException failure) {
            throw failure;
        } catch (RuntimeException unexpected) {
            throw ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
        } finally {
            releasedCount.incrementAndGet();
        }
    }

    public List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    public List<ProviderChatRequest> chatRequests() {
        return List.copyOf(chatRequests);
    }

    public List<List<String>> embeddingInputs() {
        return List.copyOf(embeddingInputs);
    }

    public List<ProviderRerankRequest> rerankRequests() {
        return List.copyOf(rerankRequests);
    }

    public int releasedCount() {
        return releasedCount.get();
    }

    public int cancelledCount() {
        return cancelledCount.get();
    }

    private void rejectIfCancelled(ProviderAttemptContext context) {
        if (context.cancellation().isCancelled()) {
            cancelledCount.incrementAndGet();
            throw ProviderFailureException.of(AttemptFailureType.CALLER_CANCELED);
        }
    }

    private Throwable normalizedFailure(Throwable failure) {
        return failure instanceof ProviderFailureException
                ? failure
                : ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
    }

    public record Invocation(Capability capability, DeploymentId deploymentId, int attemptNo) {
    }

    @FunctionalInterface
    public interface ChatBehavior {
        ProviderChatResult complete(
                ModelDeployment deployment,
                ProviderChatRequest request,
                ProviderAttemptContext context);
    }

    @FunctionalInterface
    public interface StreamBehavior {
        Flux<ProviderChatEvent> stream(
                ModelDeployment deployment,
                ProviderChatRequest request,
                ProviderAttemptContext context);
    }

    @FunctionalInterface
    public interface EmbeddingBehavior {
        ProviderEmbeddingResult embed(
                ModelDeployment deployment,
                List<String> texts,
                ProviderAttemptContext context);
    }

    @FunctionalInterface
    public interface RerankBehavior {
        ProviderRerankResult rerank(
                ModelDeployment deployment,
                ProviderRerankRequest request,
                ProviderAttemptContext context);
    }

    public record Script(
            ChatBehavior chat,
            StreamBehavior stream,
            EmbeddingBehavior embedding,
            RerankBehavior rerank) {

        public Script {
            Objects.requireNonNull(chat);
            Objects.requireNonNull(stream);
            Objects.requireNonNull(embedding);
            Objects.requireNonNull(rerank);
        }

        public static Script chat(ChatBehavior behavior) {
            return new Script(behavior, unsupportedStream(), unsupportedEmbedding(), unsupportedRerank());
        }

        public static Script stream(StreamBehavior behavior) {
            return new Script(unsupportedChat(), behavior, unsupportedEmbedding(), unsupportedRerank());
        }

        public static Script embedding(EmbeddingBehavior behavior) {
            return new Script(unsupportedChat(), unsupportedStream(), behavior, unsupportedRerank());
        }

        public static Script rerank(RerankBehavior behavior) {
            return new Script(unsupportedChat(), unsupportedStream(), unsupportedEmbedding(), behavior);
        }

        private static ChatBehavior unsupportedChat() {
            return (deployment, request, context) -> {
                throw new IllegalStateException("chat behavior is not scripted");
            };
        }

        private static StreamBehavior unsupportedStream() {
            return (deployment, request, context) -> Flux.error(
                    new IllegalStateException("stream behavior is not scripted"));
        }

        private static EmbeddingBehavior unsupportedEmbedding() {
            return (deployment, texts, context) -> {
                throw new IllegalStateException("embedding behavior is not scripted");
            };
        }

        private static RerankBehavior unsupportedRerank() {
            return (deployment, request, context) -> {
                throw new IllegalStateException("rerank behavior is not scripted");
            };
        }
    }
}
