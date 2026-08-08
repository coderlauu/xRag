package com.app.ai.chat;

import com.app.ai.model.AiRuntimePolicy;
import com.app.ai.model.CancellationToken;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatStreamEvent;
import com.app.ai.model.ModelDeployment;
import com.app.ai.provider.AttemptFailureType;
import com.app.ai.provider.AttemptLifecycle;
import com.app.ai.provider.ProviderAttemptContext;
import com.app.ai.provider.ProviderChatEvent;
import com.app.ai.provider.ProviderChatRequest;
import com.app.ai.provider.ProviderFailureException;
import com.app.ai.provider.StreamingChatProvider;
import com.app.ai.runtime.Deadline;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Scheduler;

/**
 * One upstream streaming attempt against a single deployment.
 *
 * <p>Emits business events only — the first meaningful business event is the commit point, but
 * downstream {@code StreamStarted} / {@code StreamFailed} framing and diagnostics belong to the
 * calling client. Every failure leaves this attempt as {@link ProviderFailureException}.
 */
final class CandidateStreamAttempt {

    private final ModelDeployment deployment;
    private final StreamingChatProvider provider;
    private final AiRuntimePolicy.StreamLimits limits;
    private final StreamPhaseTimeouts timeouts;
    private final Scheduler timerScheduler;
    private final long callStartedNanos;

    private final AtomicReference<State> state = new AtomicReference<>(State.PROBING);
    private final AtomicBoolean dispatched = new AtomicBoolean();
    private final AtomicBoolean connected = new AtomicBoolean();
    private final AtomicBoolean responseHeadersReceived = new AtomicBoolean();
    private final Sinks.Empty<Void> connectedSignal = Sinks.empty();
    private final Sinks.Empty<Void> responseHeadersSignal = Sinks.empty();
    private final AtomicBoolean emptyStream = new AtomicBoolean();
    private final AtomicInteger probeEventCount = new AtomicInteger();
    private final AtomicLong probeByteCount = new AtomicLong();
    private final AtomicInteger streamEventCount = new AtomicInteger();
    private final AtomicLong startedOffsetMs = new AtomicLong(-1);
    private final AtomicLong firstUpstreamEventMs = new AtomicLong(-1);
    private final AtomicLong firstMeaningfulChunkMs = new AtomicLong(-1);

    CandidateStreamAttempt(
            ModelDeployment deployment,
            StreamingChatProvider provider,
            AiRuntimePolicy.StreamLimits limits,
            StreamPhaseTimeouts timeouts,
            Scheduler timerScheduler,
            long callStartedNanos) {
        this.deployment = Objects.requireNonNull(deployment);
        this.provider = Objects.requireNonNull(provider);
        this.limits = Objects.requireNonNull(limits);
        this.timeouts = Objects.requireNonNull(timeouts);
        this.timerScheduler = Objects.requireNonNull(timerScheduler);
        this.callStartedNanos = callStartedNanos;
    }

    Flux<ChatStreamEvent> run(
            ChatCommand command,
            UUID logicalCallId,
            int attemptNo,
            Deadline deadline,
            CancellationToken cancellation,
            Runnable dispatchObserver) {
        Objects.requireNonNull(dispatchObserver);
        var context = new ProviderAttemptContext(
                logicalCallId,
                attemptNo,
                deadline,
                cancellation,
                new AttemptLifecycle() {
                    @Override
                    public void dispatched() {
                        onDispatched(dispatchObserver);
                    }

                    @Override
                    public void connected() {
                        onConnected();
                    }

                    @Override
                    public void responseHeadersReceived() {
                        onResponseHeadersReceived();
                    }
                });
        Flux<ProviderChatEvent> upstream;
        try {
            upstream = Objects.requireNonNull(provider.stream(
                    deployment,
                    new ProviderChatRequest(
                            command.messages(),
                            command.tools(),
                            deployment.chat().sampling(command.tier())),
                    context));
        } catch (RuntimeException failure) {
            upstream = Flux.error(failure);
        }
        Flux<ProviderChatEvent> firstEventBounded = withTimeouts(
                upstream,
                connectTimeout(),
                responseHeadersTimeout(),
                firstUpstreamEventTimeout());
        Flux<ChatStreamEvent> businessStream = firstEventBounded
                .concatMap(this::onProviderEvent, 1)
                .concatWith(Flux.defer(this::onUpstreamComplete));
        Flux<ChatStreamEvent> phaseBounded = withTimeouts(
                        businessStream, firstMeaningfulChunkTimeout())
                .timeout(
                        Mono.never(),
                        event -> event instanceof ChatStreamEvent.Completed
                                ? Mono.never()
                                : Mono.delay(timeouts.idle(), timerScheduler))
                .onErrorMap(
                        TimeoutException.class,
                        ignored -> ProviderFailureException.of(
                                AttemptFailureType.STREAM_IDLE_TIMEOUT));
        return withTimeouts(phaseBounded, totalTimeout(deadline.remaining()))
                .onErrorMap(this::normalized);
    }

    boolean dispatched() {
        return dispatched.get();
    }

    boolean committed() {
        return state.get() != State.PROBING;
    }

    boolean emptyStream() {
        return emptyStream.get();
    }

    int streamEventCount() {
        return streamEventCount.get();
    }

    long startedOffsetMs() {
        return Math.max(0, startedOffsetMs.get());
    }

    Long firstUpstreamEventMs() {
        return nullableOffset(firstUpstreamEventMs);
    }

    Long firstMeaningfulChunkMs() {
        return nullableOffset(firstMeaningfulChunkMs);
    }

    /**
     * Normalizes an attempt failure, refining a generic interruption into the phase that the
     * attempt actually reached.
     */
    ProviderFailureException normalized(Throwable failure) {
        ProviderFailureException typed = failure instanceof ProviderFailureException providerFailure
                ? providerFailure
                : ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
        if (state.get() == State.PROBING
                && typed.failureType() == AttemptFailureType.UPSTREAM_STREAM_INTERRUPTED) {
            return ProviderFailureException.of(firstUpstreamEventMs.get() < 0
                    ? AttemptFailureType.UPSTREAM_RESET_BEFORE_FIRST_EVENT
                    : AttemptFailureType.UPSTREAM_RESET_DURING_PROBE);
        }
        return typed;
    }

    @SafeVarargs
    private final <T> Flux<T> withTimeouts(Flux<T> source, Mono<Void>... timeoutSignals) {
        var publishers = new ArrayList<Flux<Signal<T>>>(timeoutSignals.length + 1);
        publishers.add(source.materialize());
        for (Mono<Void> timeoutSignal : timeoutSignals) {
            publishers.add(timeoutSignal.thenMany(Flux.never()));
        }
        return Flux.merge(publishers)
                .takeUntil(Signal::isOnComplete)
                .dematerialize();
    }

    private Mono<Void> totalTimeout(Duration remaining) {
        return Mono.delay(remaining, timerScheduler)
                .then(Mono.error(ProviderFailureException.of(
                        AttemptFailureType.TOTAL_DEADLINE_EXCEEDED)));
    }

    private Mono<Void> connectTimeout() {
        return Mono.delay(timeouts.connect(), timerScheduler)
                .flatMap(ignored -> connected.get()
                        ? Mono.never()
                        : Mono.error(ProviderFailureException.of(
                                AttemptFailureType.CONNECT_TIMEOUT)));
    }

    private Mono<Void> responseHeadersTimeout() {
        return connectedSignal.asMono()
                .then(Mono.delay(timeouts.responseHeaders(), timerScheduler))
                .flatMap(ignored -> responseHeadersReceived.get()
                        ? Mono.never()
                        : Mono.error(ProviderFailureException.of(
                                AttemptFailureType.RESPONSE_HEADERS_TIMEOUT)));
    }

    private Mono<Void> firstUpstreamEventTimeout() {
        return responseHeadersSignal.asMono()
                .then(Mono.delay(timeouts.firstUpstreamEvent(), timerScheduler))
                .flatMap(ignored -> firstUpstreamEventMs.get() >= 0
                        ? Mono.never()
                        : Mono.error(ProviderFailureException.of(
                                AttemptFailureType.FIRST_UPSTREAM_EVENT_TIMEOUT)));
    }

    private Mono<Void> firstMeaningfulChunkTimeout() {
        return responseHeadersSignal.asMono()
                .then(Mono.delay(timeouts.firstMeaningfulChunk(), timerScheduler))
                .flatMap(ignored -> firstMeaningfulChunkMs.get() >= 0
                        ? Mono.never()
                        : Mono.error(ProviderFailureException.of(
                                AttemptFailureType.FIRST_MEANINGFUL_CHUNK_TIMEOUT)));
    }

    private Flux<ChatStreamEvent> onProviderEvent(ProviderChatEvent event) {
        long eventOffsetMs = elapsedMillis(callStartedNanos);
        firstUpstreamEventMs.compareAndSet(-1, eventOffsetMs);
        streamEventCount.incrementAndGet();
        if (event == null) {
            return invalidProviderEvent();
        }
        if (event instanceof ProviderChatEvent.Completed completed) {
            return onCompleted(completed);
        }
        if (event instanceof ProviderChatEvent.ProbeSignal signal) {
            return onProbeSignal(signal.byteCount());
        }
        if (event instanceof ProviderChatEvent.TextDelta text) {
            if (text.text() == null) {
                return invalidProviderEvent();
            }
            if (text.text().isEmpty()) {
                return onProbeSignal(0);
            }
        }
        if (event instanceof ProviderChatEvent.RefusalDelta refusal) {
            if (refusal.text() == null) {
                return invalidProviderEvent();
            }
            if (refusal.text().isEmpty()) {
                return onProbeSignal(0);
            }
        }
        if (!isMeaningful(event)) {
            return invalidProviderEvent();
        }
        ChatStreamEvent publicEvent = publicEvent(event);
        firstMeaningfulChunkMs.compareAndSet(-1, eventOffsetMs);
        state.compareAndSet(State.PROBING, State.COMMITTED);
        return state.get() == State.COMMITTED ? Flux.just(publicEvent) : Flux.empty();
    }

    private Flux<ChatStreamEvent> onProbeSignal(int byteCount) {
        if (state.get() != State.PROBING) {
            return Flux.empty();
        }
        int events = probeEventCount.incrementAndGet();
        long bytes = probeByteCount.addAndGet(byteCount);
        if (events >= limits.probeMaxEvents() || bytes >= limits.probeMaxBytes()) {
            return invalidProviderEvent();
        }
        return Flux.empty();
    }

    private Flux<ChatStreamEvent> invalidProviderEvent() {
        return Flux.error(ProviderFailureException.of(
                AttemptFailureType.PROVIDER_RESPONSE_INVALID));
    }

    private Flux<ChatStreamEvent> onCompleted(ProviderChatEvent.Completed completed) {
        if (completed.finishReason() == null) {
            return invalidProviderEvent();
        }
        if (state.compareAndSet(State.COMMITTED, State.COMPLETED)) {
            return Flux.just(new ChatStreamEvent.Completed(
                    completed.finishReason(), completed.usage()));
        }
        if (state.get() == State.PROBING) {
            emptyStream.set(true);
            return invalidProviderEvent();
        }
        return Flux.empty();
    }

    private Flux<ChatStreamEvent> onUpstreamComplete() {
        return switch (state.get()) {
            case COMPLETED -> Flux.empty();
            case PROBING -> Flux.error(ProviderFailureException.of(
                    firstUpstreamEventMs.get() < 0
                            ? AttemptFailureType.UPSTREAM_RESET_BEFORE_FIRST_EVENT
                            : AttemptFailureType.UPSTREAM_RESET_DURING_PROBE));
            case COMMITTED -> Flux.error(ProviderFailureException.of(
                    AttemptFailureType.UPSTREAM_STREAM_INTERRUPTED));
        };
    }

    private void onDispatched(Runnable dispatchObserver) {
        if (dispatched.compareAndSet(false, true)) {
            startedOffsetMs.compareAndSet(-1, elapsedMillis(callStartedNanos));
            dispatchObserver.run();
        }
    }

    private void onConnected() {
        if (connected.compareAndSet(false, true)) {
            connectedSignal.tryEmitEmpty();
        }
    }

    private void onResponseHeadersReceived() {
        if (responseHeadersReceived.compareAndSet(false, true)) {
            responseHeadersSignal.tryEmitEmpty();
        }
    }

    private static boolean isMeaningful(ProviderChatEvent event) {
        if (event instanceof ProviderChatEvent.TextDelta text) {
            return text.text() != null && !text.text().isEmpty();
        }
        if (event instanceof ProviderChatEvent.RefusalDelta refusal) {
            return refusal.text() != null && !refusal.text().isEmpty();
        }
        if (event instanceof ProviderChatEvent.ToolCallDelta tool) {
            return tool.callId() != null && !tool.callId().isBlank()
                    && tool.index() >= 0
                    && tool.name() != null && !tool.name().isBlank()
                    && tool.argumentsFragment() != null;
        }
        return false;
    }

    private static ChatStreamEvent publicEvent(ProviderChatEvent event) {
        if (event instanceof ProviderChatEvent.TextDelta text) {
            return new ChatStreamEvent.TextDelta(text.text());
        }
        if (event instanceof ProviderChatEvent.RefusalDelta refusal) {
            return new ChatStreamEvent.RefusalDelta(refusal.text());
        }
        if (event instanceof ProviderChatEvent.ToolCallDelta tool) {
            return new ChatStreamEvent.ToolCallDelta(
                    tool.callId(), tool.index(), tool.name(), tool.argumentsFragment());
        }
        throw new IllegalArgumentException("event must be a meaningful business event");
    }

    private static Long nullableOffset(AtomicLong offset) {
        long value = offset.get();
        return value < 0 ? null : value;
    }

    static long elapsedMillis(long startedNanos) {
        return Math.max(0, (System.nanoTime() - startedNanos) / 1_000_000);
    }

    private enum State {
        PROBING,
        COMMITTED,
        COMPLETED
    }
}
