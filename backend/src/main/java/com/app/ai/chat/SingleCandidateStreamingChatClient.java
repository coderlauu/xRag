package com.app.ai.chat;

import com.app.ai.diagnostic.DiagnosticRecorder;
import com.app.ai.model.CallOptions;
import com.app.ai.model.Capability;
import com.app.ai.model.ChatCommand;
import com.app.ai.model.ChatStreamEvent;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ExecutionInfo;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.ModelCallException;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.RequestId;
import com.app.ai.model.StreamingChatClient;
import com.app.ai.model.TerminalCause;
import com.app.ai.model.TerminationReason;
import com.app.ai.provider.AttemptFailureType;
import com.app.ai.provider.ProviderAttemptContext;
import com.app.ai.provider.ProviderChatEvent;
import com.app.ai.provider.ProviderChatRequest;
import com.app.ai.provider.ProviderFailureException;
import com.app.ai.provider.StreamingChatProvider;
import com.app.ai.runtime.FailureAction;
import com.app.ai.runtime.FailureActionPolicy;
import com.app.ai.runtime.MonotonicDeadline;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;

public final class SingleCandidateStreamingChatClient implements StreamingChatClient {

    private static final String SECOND_SUBSCRIPTION_MESSAGE =
            "stream publisher allows only one subscription";

    private final ModelCatalogSnapshot catalog;
    private final ModelDeployment deployment;
    private final ModelProvider modelProvider;
    private final StreamingChatProvider provider;
    private final DiagnosticRecorder diagnostics;

    public SingleCandidateStreamingChatClient(
            ModelCatalogSnapshot catalog,
            DeploymentId deploymentId,
            StreamingChatProvider provider,
            DiagnosticRecorder diagnostics) {
        this.catalog = Objects.requireNonNull(catalog);
        this.deployment = Objects.requireNonNull(
                catalog.deployments().get(Objects.requireNonNull(deploymentId)),
                "deployment must exist in catalog");
        this.modelProvider = Objects.requireNonNull(
                catalog.providers().get(deployment.providerId()),
                "deployment provider must exist in catalog");
        if (!deployment.capabilities().contains(Capability.STREAM_CHAT)) {
            throw new IllegalArgumentException("deployment must support STREAM_CHAT");
        }
        this.provider = Objects.requireNonNull(provider);
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    @Override
    public Flux<ChatStreamEvent> stream(ChatCommand command) {
        Objects.requireNonNull(command);
        AtomicBoolean subscribed = new AtomicBoolean();
        return Flux.defer(() -> {
            if (!subscribed.compareAndSet(false, true)) {
                return Flux.error(new IllegalStateException(SECOND_SUBSCRIPTION_MESSAGE));
            }
            return new StreamExecution(command).run();
        });
    }

    private final class StreamExecution {

        private final ChatCommand command;
        private final UUID logicalCallId = UUID.randomUUID();
        private final RequestId requestId;
        private final ExecutionTarget target;
        private final AtomicReference<State> state = new AtomicReference<>(State.PROBING);
        private final AtomicBoolean dispatched = new AtomicBoolean();
        private final AtomicInteger probeEventCount = new AtomicInteger();
        private final AtomicLong probeByteCount = new AtomicLong();

        private StreamExecution(ChatCommand command) {
            this.command = command;
            this.requestId = requestId(command.options());
            this.target = new ExecutionTarget(
                    deployment.providerId(), deployment.id(), deployment.modelIdentity());
        }

        private Flux<ChatStreamEvent> run() {
            var deadline = MonotonicDeadline.start(
                    catalog.runtimePolicy().streamChat().totalTimeout(),
                    command.options().timeout());
            var context = new ProviderAttemptContext(
                    logicalCallId,
                    1,
                    deadline,
                    command.options().cancellation(),
                    () -> dispatched.compareAndSet(false, true));
            Flux<ProviderChatEvent> upstream;
            try {
                upstream = Objects.requireNonNull(provider.stream(
                        deployment,
                        new ProviderChatRequest(command.messages(), command.tools()),
                        context));
            } catch (RuntimeException failure) {
                upstream = Flux.error(normalizedFailure(failure));
            }
            return upstream
                    .concatMap(this::onProviderEvent, 1)
                    .concatWith(Flux.defer(this::onUpstreamComplete))
                    .onErrorResume(this::onFailure)
                    .doOnCancel(this::onCancel);
        }

        private Flux<ChatStreamEvent> onProviderEvent(ProviderChatEvent event) {
            if (event == null) {
                return Flux.error(ProviderFailureException.of(
                        AttemptFailureType.PROVIDER_RESPONSE_INVALID));
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
            if (event instanceof ProviderChatEvent.ToolCallDelta tool && !isMeaningful(tool)) {
                return invalidProviderEvent();
            }
            if (!isMeaningful(event)) {
                return invalidProviderEvent();
            }
            ChatStreamEvent publicEvent = publicEvent(event);
            if (state.compareAndSet(State.PROBING, State.COMMITTED)) {
                var execution = new ExecutionInfo(logicalCallId, requestId, target, 1, 0, 0);
                return Flux.just(new ChatStreamEvent.StreamStarted(execution), publicEvent);
            }
            return state.get() == State.COMMITTED ? Flux.just(publicEvent) : Flux.empty();
        }

        private Flux<ChatStreamEvent> onProbeSignal(int byteCount) {
            if (state.get() != State.PROBING) {
                return Flux.empty();
            }
            int events = probeEventCount.incrementAndGet();
            long bytes = probeByteCount.addAndGet(byteCount);
            var limits = catalog.runtimePolicy().stream();
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
                return Flux.error(ProviderFailureException.of(
                        AttemptFailureType.PROVIDER_RESPONSE_INVALID));
            }
            if (state.compareAndSet(State.COMMITTED, State.COMPLETED)) {
                return Flux.just(new ChatStreamEvent.Completed(
                        completed.finishReason(), completed.usage()));
            }
            if (state.get() == State.PROBING) {
                return Flux.error(ProviderFailureException.of(
                        AttemptFailureType.PROVIDER_RESPONSE_INVALID));
            }
            return Flux.empty();
        }

        private Flux<ChatStreamEvent> onUpstreamComplete() {
            return switch (state.get()) {
                case COMPLETED, STREAM_FAILED, FAILED, CANCELED -> Flux.empty();
                case PROBING -> Flux.error(ProviderFailureException.of(
                        AttemptFailureType.UPSTREAM_RESET_BEFORE_FIRST_EVENT));
                case COMMITTED -> Flux.error(ProviderFailureException.of(
                        AttemptFailureType.UPSTREAM_STREAM_INTERRUPTED));
            };
        }

        private Flux<ChatStreamEvent> onFailure(Throwable failure) {
            ProviderFailureException normalized = normalizedFailure(failure);
            if (state.compareAndSet(State.PROBING, State.FAILED)) {
                return Flux.error(publicFailure(normalized, false));
            }
            if (state.compareAndSet(State.COMMITTED, State.STREAM_FAILED)) {
                TerminalCause cause = terminalCause(normalized, true);
                return Flux.just(new ChatStreamEvent.StreamFailed(
                        cause, stableMessage(cause.errorType()), true));
            }
            return Flux.empty();
        }

        private void onCancel() {
            state.compareAndSet(State.PROBING, State.CANCELED);
            state.compareAndSet(State.COMMITTED, State.CANCELED);
        }

        private ModelCallException publicFailure(
                ProviderFailureException failure, boolean committed) {
            FailureAction action = FailureActionPolicy.actionFor(failure.failureType());
            var execution = new ExecutionInfo(
                    logicalCallId, requestId, null, dispatched.get() ? 1 : 0, 0, 0);
            TerminalCause cause = terminalCause(failure, committed);
            return new ModelCallException(
                    stableMessage(action.errorType()),
                    execution,
                    cause,
                    retryableByCaller(action.errorType()));
        }

        private TerminalCause terminalCause(
                ProviderFailureException failure, boolean committed) {
            FailureAction action = FailureActionPolicy.actionFor(failure.failureType());
            TerminationReason termination = FailureActionPolicy.terminationReason(
                    failure.failureType(), committed);
            return new TerminalCause(
                    action.errorType(),
                    action.failurePhase(),
                    termination,
                    failure.httpStatus(),
                    dispatched.get() ? target : null);
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

    private static ProviderFailureException normalizedFailure(Throwable failure) {
        return failure instanceof ProviderFailureException providerFailure
                ? providerFailure
                : ProviderFailureException.of(AttemptFailureType.PROVIDER_RESPONSE_INVALID);
    }

    private static RequestId requestId(CallOptions options) {
        return options.requestId() == null
                ? new RequestId("request-" + UUID.randomUUID())
                : options.requestId();
    }

    private static boolean retryableByCaller(ModelErrorType errorType) {
        return switch (errorType) {
            case RATE_LIMITED, NETWORK, SERVER_ERROR, TIMEOUT, STREAM_INTERRUPTED -> true;
            default -> false;
        };
    }

    private static String stableMessage(ModelErrorType errorType) {
        return switch (errorType) {
            case INVALID_REQUEST -> "模型调用参数无效";
            case NO_ELIGIBLE_MODEL -> "没有符合条件的模型";
            case AUTHENTICATION -> "模型供应商认证失败";
            case PERMISSION -> "模型供应商权限不足";
            case RATE_LIMITED -> "模型供应商请求受限";
            case NETWORK -> "模型供应商网络异常";
            case SERVER_ERROR -> "模型供应商服务异常";
            case TIMEOUT -> "模型调用超时";
            case PROTOCOL_CONTRACT -> "模型供应商响应不符合协议";
            case CANCELED -> "模型调用已取消";
            case STREAM_INTERRUPTED -> "模型流式响应中断";
        };
    }

    private enum State {
        PROBING,
        COMMITTED,
        COMPLETED,
        FAILED,
        STREAM_FAILED,
        CANCELED
    }
}
