package com.app.qa.generation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public final class SseGenerationObserver implements GenerationObserver {

    private static final Duration DELTA_FLUSH_INTERVAL = Duration.ofMillis(20);
    private static final int DELTA_FLUSH_CHARACTERS = 256;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Sink sink;
    private final Executor pushExecutor;
    private final ScheduledExecutorService timers;
    private final Clock clock;
    private final int capacity;
    private final Object monitor = new Object();
    private final ArrayDeque<GenerationEvent> mailbox = new ArrayDeque<>();
    private final AtomicBoolean draining = new AtomicBoolean();

    private PendingDelta pendingDelta;
    private ScheduledFuture<?> deltaTimer;
    private boolean terminalQueued;
    private boolean closed;

    public SseGenerationObserver(
            SseEmitter emitter,
            Executor pushExecutor,
            ScheduledExecutorService timers,
            Clock clock,
            int capacity) {
        this(new EmitterSink(emitter), pushExecutor, timers, clock, capacity);
    }

    SseGenerationObserver(
            Sink sink,
            Executor pushExecutor,
            ScheduledExecutorService timers,
            Clock clock,
            int capacity) {
        if (sink == null || pushExecutor == null || timers == null || clock == null) {
            throw new IllegalArgumentException("SSE observer collaborators must not be null");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("SSE mailbox capacity must be positive");
        }
        this.sink = sink;
        this.pushExecutor = pushExecutor;
        this.timers = timers;
        this.clock = clock;
        this.capacity = capacity;
    }

    @Override
    public void onEvent(GenerationEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        boolean shouldDrain = false;
        synchronized (monitor) {
            if (closed || terminalQueued) {
                return;
            }
            if (event instanceof GenerationEvent.AnswerDelta delta) {
                ensureCapacity(mailbox.size() + (pendingDelta == null ? 1 : 0));
                if (pendingDelta == null) {
                    pendingDelta = new PendingDelta(delta.sequence());
                    deltaTimer = timers.schedule(
                            this::flushPendingFromTimer,
                            DELTA_FLUSH_INTERVAL.toNanos(),
                            TimeUnit.NANOSECONDS);
                }
                pendingDelta.append(delta);
                if (pendingDelta.characters() >= DELTA_FLUSH_CHARACTERS) {
                    flushPendingLocked();
                    shouldDrain = true;
                }
            } else {
                int required = mailbox.size() + (pendingDelta == null ? 0 : 1) + 1;
                ensureCapacity(required);
                if (pendingDelta != null) {
                    flushPendingLocked();
                }
                mailbox.addLast(event);
                terminalQueued = isTerminal(event);
                shouldDrain = true;
            }
        }
        if (shouldDrain) {
            scheduleDrain();
        }
    }

    private void ensureCapacity(int required) {
        if (required <= capacity) {
            return;
        }
        mailbox.clear();
        pendingDelta = null;
        cancelDeltaTimer();
        throw new PushBackpressureException();
    }

    private void flushPendingFromTimer() {
        synchronized (monitor) {
            if (closed || pendingDelta == null) {
                return;
            }
            flushPendingLocked();
        }
        scheduleDrain();
    }

    private void flushPendingLocked() {
        var delta = pendingDelta;
        pendingDelta = null;
        cancelDeltaTimer();
        mailbox.addLast(new GenerationEvent.AnswerDelta(delta.sequence, delta.text.toString()));
    }

    private void cancelDeltaTimer() {
        if (deltaTimer != null) {
            deltaTimer.cancel(false);
            deltaTimer = null;
        }
    }

    private void scheduleDrain() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            pushExecutor.execute(this::drain);
        } catch (RejectedExecutionException exception) {
            draining.set(false);
            synchronized (monitor) {
                mailbox.clear();
                pendingDelta = null;
                cancelDeltaTimer();
            }
            throw new PushBackpressureException();
        }
    }

    private void drain() {
        try {
            while (true) {
                GenerationEvent event;
                synchronized (monitor) {
                    event = mailbox.pollFirst();
                    if (event == null) {
                        draining.set(false);
                        if (!mailbox.isEmpty() && draining.compareAndSet(false, true)) {
                            continue;
                        }
                        return;
                    }
                }
                try {
                    sink.send(frame(event));
                    if (isTerminal(event)) {
                        synchronized (monitor) {
                            closed = true;
                            mailbox.clear();
                            pendingDelta = null;
                            cancelDeltaTimer();
                        }
                        sink.complete();
                        return;
                    }
                } catch (Exception transportFailure) {
                    synchronized (monitor) {
                        closed = true;
                        mailbox.clear();
                        pendingDelta = null;
                        cancelDeltaTimer();
                    }
                    return;
                }
            }
        } finally {
            draining.set(false);
        }
    }

    private Frame frame(GenerationEvent event) {
        var data = new LinkedHashMap<String, Object>();
        data.put("sequence", event.sequence());
        data.put("occurredAt", Instant.now(clock).toString());
        String name;
        if (event instanceof GenerationEvent.Accepted accepted) {
            name = "accepted";
            data.put("conversationId", accepted.conversationId());
            data.put("turnId", accepted.turnId());
            data.put("taskId", accepted.taskId().value());
            data.put("userMessageId", accepted.userMessageId());
        } else if (event instanceof GenerationEvent.Queued) {
            name = "queued";
        } else if (event instanceof GenerationEvent.GenerationStarted started) {
            name = "generation_started";
            data.put("queuedOnce", started.queuedOnce());
        } else if (event instanceof GenerationEvent.AnswerDelta delta) {
            name = "answer_delta";
            data.put("text", delta.text());
        } else if (event instanceof GenerationEvent.Completed completed) {
            name = "completed";
            data.put("assistantMessageId", completed.assistantMessageId());
            data.put("saved", completed.saved());
        } else if (event instanceof GenerationEvent.Stopped stopped) {
            name = "stopped";
            data.put("assistantMessageId", stopped.assistantMessageId());
            data.put("saved", stopped.saved());
            data.put("hasPartialAnswer", stopped.hasPartialAnswer());
        } else if (event instanceof GenerationEvent.Failed failed) {
            name = "failed";
            data.put("code", failed.code().name());
            data.put("retryable", failed.retryable());
            data.put("assistantMessageId", failed.assistantMessageId());
            data.put("saved", failed.saved());
        } else if (event instanceof GenerationEvent.TimedOut) {
            name = "timed_out";
            data.put("retryable", true);
        } else {
            throw new IllegalArgumentException("unknown generation event");
        }
        try {
            return new Frame(name, Long.toString(event.sequence()), JSON.writeValueAsString(data));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("cannot encode generation event", exception);
        }
    }

    private static boolean isTerminal(GenerationEvent event) {
        return event instanceof GenerationEvent.Completed
                || event instanceof GenerationEvent.Stopped
                || event instanceof GenerationEvent.Failed
                || event instanceof GenerationEvent.TimedOut;
    }

    interface Sink {
        void send(Frame frame) throws Exception;

        void complete();
    }

    record Frame(String event, String id, String data) {}

    private static final class PendingDelta {
        private long sequence;
        private final StringBuilder text = new StringBuilder();

        private PendingDelta(long sequence) {
            this.sequence = sequence;
        }

        private void append(GenerationEvent.AnswerDelta delta) {
            sequence = delta.sequence();
            text.append(delta.text());
        }

        private int characters() {
            return text.codePointCount(0, text.length());
        }
    }

    private static final class EmitterSink implements Sink {
        private final SseEmitter emitter;

        private EmitterSink(SseEmitter emitter) {
            this.emitter = emitter;
        }

        @Override
        public void send(Frame frame) throws Exception {
            emitter.send(SseEmitter.event()
                    .name(frame.event())
                    .id(frame.id())
                    .data(JSON.readTree(frame.data()), MediaType.APPLICATION_JSON));
        }

        @Override
        public void complete() {
            emitter.complete();
        }
    }
}
