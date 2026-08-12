package com.app.qa.generation;

import com.app.ai.model.ChatStreamEvent;
import com.app.ai.model.CancellationToken;
import com.app.ai.model.StreamingChatClient;
import com.app.qa.prompt.GenerationPromptAssembler;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.time.Duration;
import reactor.core.Disposable;

final class DefaultGenerationTasks implements GenerationTasks, AutoCloseable {

    private final StreamingChatClient streamingChat;
    private final GenerationPromptAssembler prompts;
    private final GenerationTaskStore taskStore;
    private final TaskSettlementRepository settlements;
    private final FairGenerationAdmissionQueue admissions;
    private final Executor generationExecutor;
    private final ScheduledExecutorService timers;
    private final Duration firstByteTimeout;
    private final Duration modelDeadline;
    private final Map<GenerationTaskId, RuntimeTask> activeTasks = new ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean closing = new java.util.concurrent.atomic.AtomicBoolean();

    DefaultGenerationTasks(
            StreamingChatClient streamingChat,
            GenerationPromptAssembler prompts,
            GenerationTaskStore taskStore,
            TaskSettlementRepository settlements,
            FairGenerationAdmissionQueue admissions,
            Executor generationExecutor,
            ScheduledExecutorService timers,
            Duration firstByteTimeout,
            Duration modelDeadline) {
        this.streamingChat = streamingChat;
        this.prompts = prompts;
        this.taskStore = taskStore;
        this.settlements = settlements;
        this.admissions = admissions;
        this.generationExecutor = generationExecutor;
        this.timers = timers;
        this.firstByteTimeout = firstByteTimeout;
        this.modelDeadline = modelDeadline;
    }

    @Override
    public Submission submit(GenerationCommand command, GenerationObserver observer) {
        if (command == null || observer == null) {
            throw new IllegalArgumentException("command and observer must not be null");
        }
        if (closing.get()) {
            throw new IllegalStateException("generation service is shutting down");
        }
        taskStore.validateReady(command);
        var prompt = prompts.assemble(command.promptMaterial()).command();
        var taskId = new GenerationTaskId(UUID.randomUUID());
        var runtime = new RuntimeTask(taskId, command, observer, prompt);

        var admission = admissions.admit(taskId, CancellationToken.none(), runtime);
        if (admission == FairGenerationAdmissionQueue.Admission.FULL) {
            throw new GenerationQueueFullException();
        }
        if (admission == FairGenerationAdmissionQueue.Admission.CANCELLED) {
            throw new IllegalStateException("new generation task was unexpectedly cancelled");
        }
        boolean queued = admission == FairGenerationAdmissionQueue.Admission.QUEUED;
        runtime.queuedOnce = queued;
        try {
            taskStore.create(taskId, command, queued);
            activeTasks.put(taskId, runtime);
            runtime.emit(new GenerationEvent.Accepted(
                    runtime.nextSequence(), taskId, command.conversationId(), command.turnId(),
                    command.userMessageId()));
            if (queued) {
                runtime.emit(new GenerationEvent.Queued(runtime.nextSequence()));
            }
            runtime.persisted.complete(null);
        } catch (RuntimeException exception) {
            admissions.cancel(taskId);
            runtime.persisted.completeExceptionally(exception);
            runtime.releasePermit();
            throw exception;
        }
        return new Submission(taskId, queued);
    }

    @Override
    public StopResult stop(OwnedTask ownedTask) {
        if (ownedTask == null) {
            throw new IllegalArgumentException("owned task must not be null");
        }
        var runtime = activeTasks.get(ownedTask.taskId());
        if (runtime == null || !runtime.ownedBy(ownedTask)) {
            return taskStore.findOwned(ownedTask)
                    .map(task -> StopResult.stopped(task.status(), task.status().isTerminal()))
                    .orElseGet(StopResult::notFound);
        }

        admissions.cancel(ownedTask.taskId());
        var outcome = runtime.settlement.trySettle(TaskSettlement.Request.stopped(runtime.answer()));
        return StopResult.stopped(outcome.status(), !outcome.won());
    }

    @Override
    public void close() {
        if (!closing.compareAndSet(false, true)) {
            return;
        }
        for (var runtime : List.copyOf(activeTasks.values())) {
            admissions.cancel(runtime.taskId);
            runtime.settlement.trySettle(TaskSettlement.Request.failed(
                    GenerationFailureCode.SERVICE_SHUTDOWN, runtime.answer()));
        }
    }

    private final class RuntimeTask implements FairGenerationAdmissionQueue.Listener {
        private final GenerationTaskId taskId;
        private final GenerationCommand command;
        private final GenerationObserver observer;
        private final com.app.ai.model.ChatCommand prompt;
        private final CompletableFuture<Void> persisted = new CompletableFuture<>();
        private final AtomicReference<FairGenerationAdmissionQueue.Permit> permit = new AtomicReference<>();
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> firstByteTimer = new AtomicReference<>();
        private final AtomicReference<ScheduledFuture<?>> modelTimer = new AtomicReference<>();
        private final StringBuilder answer = new StringBuilder();
        private final TaskSettlement settlement;

        private long sequence;
        private boolean terminal;
        private boolean queuedOnce;

        private RuntimeTask(
                GenerationTaskId taskId,
                GenerationCommand command,
                GenerationObserver observer,
                com.app.ai.model.ChatCommand prompt) {
            this.taskId = taskId;
            this.command = command;
            this.observer = observer;
            this.prompt = prompt;
            this.settlement = new TaskSettlement(
                    taskId,
                    settlements,
                    this::releaseResources,
                    this::emitTerminal);
        }

        @Override
        public void onGranted(FairGenerationAdmissionQueue.Permit granted) {
            permit.set(granted);
            generationExecutor.execute(this::startAfterPersistence);
        }

        @Override
        public void onTimedOut() {
            persisted.thenRun(() -> settlement.trySettle(TaskSettlement.Request.timedOut()));
        }

        @Override
        public void onCapacityMismatch() {
            persisted.thenRun(() -> settlement.trySettle(TaskSettlement.Request.failed(
                    GenerationFailureCode.INTERNAL_CAPACITY_MISMATCH, answer())));
        }

        private void startAfterPersistence() {
            try {
                persisted.join();
            } catch (CompletionException exception) {
                releasePermit();
                return;
            }
            if (!taskStore.markGenerating(taskId)) {
                var stored = taskStore.findOwned(new OwnedTask(
                        taskId, command.conversationId(), command.turnId()));
                if (stored.isPresent() && stored.get().status().isTerminal()) {
                    releaseResources();
                    return;
                }
            }
            emit(new GenerationEvent.GenerationStarted(nextSequence(), queuedOnce));
            if (isTerminal()) {
                return;
            }
            firstByteTimer.set(timers.schedule(
                    () -> settlement.trySettle(TaskSettlement.Request.failed(
                            GenerationFailureCode.FIRST_BYTE_TIMEOUT, answer())),
                    firstByteTimeout.toNanos(), TimeUnit.NANOSECONDS));
            modelTimer.set(timers.schedule(
                    () -> settlement.trySettle(TaskSettlement.Request.failed(
                            GenerationFailureCode.GENERATION_TIMEOUT, answer())),
                    modelDeadline.toNanos(), TimeUnit.NANOSECONDS));
            Disposable disposable = streamingChat.stream(prompt).subscribe(
                    this::onModelEvent,
                    this::onModelFailure,
                    this::onModelComplete);
            subscription.set(disposable);
            if (isTerminal()) {
                var completed = subscription.getAndSet(null);
                if (completed != null) completed.dispose();
            }
        }

        private void onModelEvent(ChatStreamEvent event) {
            cancelTimer(firstByteTimer);
            if (event instanceof ChatStreamEvent.TextDelta delta) {
                emitDelta(delta.text());
            } else if (event instanceof ChatStreamEvent.RefusalDelta refusal) {
                emitDelta(refusal.text());
            } else if (event instanceof ChatStreamEvent.ToolCallDelta) {
                settlement.trySettle(TaskSettlement.Request.failed(
                        GenerationFailureCode.INTERNAL_CAPACITY_MISMATCH, answer()));
            } else if (event instanceof ChatStreamEvent.StreamFailed) {
                settlement.trySettle(TaskSettlement.Request.failed(
                        GenerationFailureCode.MODEL_UNAVAILABLE, answer()));
            } else if (event instanceof ChatStreamEvent.Completed) {
                settlement.trySettle(TaskSettlement.Request.completed(answer()));
            }
        }

        private void onModelFailure(Throwable failure) {
            var code = failure instanceof java.util.concurrent.TimeoutException
                    ? GenerationFailureCode.GENERATION_TIMEOUT
                    : GenerationFailureCode.MODEL_UNAVAILABLE;
            settlement.trySettle(TaskSettlement.Request.failed(code, answer()));
        }

        private void onModelComplete() {
            if (!isTerminal()) {
                settlement.trySettle(TaskSettlement.Request.completed(answer()));
            }
        }

        private synchronized void emitDelta(String text) {
            if (terminal || text == null || text.isEmpty()) {
                return;
            }
            answer.append(text);
            emit(new GenerationEvent.AnswerDelta(++sequence, text));
        }

        private void emitTerminal(TaskSettlement.Outcome outcome) {
            GenerationEvent event;
            synchronized (this) {
                if (terminal) {
                    return;
                }
                terminal = true;
                long terminalSequence = ++sequence;
                event = switch (outcome.status()) {
                    case COMPLETED -> new GenerationEvent.Completed(
                            terminalSequence, outcome.assistantMessageId(), outcome.saved());
                    case STOPPED -> new GenerationEvent.Stopped(
                            terminalSequence,
                            outcome.assistantMessageId(),
                            outcome.saved(),
                            !answer.isEmpty());
                    case FAILED -> new GenerationEvent.Failed(
                            terminalSequence,
                            failureCode(outcome.terminalReason()),
                            retryable(outcome.terminalReason()),
                            outcome.assistantMessageId(),
                            outcome.saved());
                    case TIMED_OUT -> new GenerationEvent.TimedOut(terminalSequence);
                    default -> throw new IllegalStateException("non-terminal settlement outcome");
                };
            }
            emit(event);
        }

        private synchronized long nextSequence() {
            return ++sequence;
        }

        private synchronized String answer() {
            return answer.toString();
        }

        private synchronized boolean isTerminal() {
            return terminal;
        }

        private boolean ownedBy(OwnedTask ownedTask) {
            return command.conversationId().equals(ownedTask.conversationId())
                    && command.turnId().equals(ownedTask.turnId());
        }

        private void emit(GenerationEvent event) {
            try {
                observer.onEvent(event);
            } catch (PushBackpressureException exception) {
                settlement.trySettle(TaskSettlement.Request.failed(
                        GenerationFailureCode.PUSH_BACKPRESSURE, answer()));
            } catch (RuntimeException ignored) {
                // Observer transport failure detaches the current delivery path; it never owns task cancellation.
            }
        }

        private void releaseResources() {
            cancelTimer(firstByteTimer);
            cancelTimer(modelTimer);
            var disposable = subscription.getAndSet(null);
            if (disposable != null) {
                disposable.dispose();
            }
            releasePermit();
            activeTasks.remove(taskId, this);
        }

        private void cancelTimer(AtomicReference<ScheduledFuture<?>> timer) {
            var scheduled = timer.getAndSet(null);
            if (scheduled != null) scheduled.cancel(false);
        }

        private void releasePermit() {
            var granted = permit.getAndSet(null);
            if (granted != null) {
                granted.close();
            }
        }
    }

    private static GenerationFailureCode failureCode(String terminalReason) {
        try {
            return GenerationFailureCode.valueOf(terminalReason);
        } catch (IllegalArgumentException exception) {
            return GenerationFailureCode.MODEL_UNAVAILABLE;
        }
    }

    private static boolean retryable(String terminalReason) {
        return terminalReason.equals(GenerationFailureCode.MODEL_UNAVAILABLE.name())
                || terminalReason.equals(GenerationFailureCode.FIRST_BYTE_TIMEOUT.name())
                || terminalReason.equals(GenerationFailureCode.GENERATION_TIMEOUT.name());
    }
}
