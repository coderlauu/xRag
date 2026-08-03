package com.app.ai.diagnostic;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AsyncDiagnosticWriter
        implements DiagnosticRecorder, BreakerTransitionRecorder, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AsyncDiagnosticWriter.class);
    private static final long WARNING_INTERVAL_NANOS = Duration.ofMinutes(1).toNanos();

    private final DiagnosticStore store;
    private final BlockingQueue<PendingDiagnostic> queue;
    private final int batchSize;
    private final Duration flushInterval;
    private final Duration closeTimeout;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong droppedCount = new AtomicLong();
    private final AtomicLong writeFailureCount = new AtomicLong();
    private final AtomicLong lastWarningNanos = new AtomicLong();
    private final Thread worker;

    @Autowired
    public AsyncDiagnosticWriter(DiagnosticStore store) {
        this(store, 1_000, 50, Duration.ofMillis(250), Duration.ofSeconds(2));
    }

    public AsyncDiagnosticWriter(
            DiagnosticStore store,
            int capacity,
            int batchSize,
            Duration flushInterval,
            Duration closeTimeout) {
        this.store = Objects.requireNonNull(store);
        if (capacity <= 0 || batchSize <= 0) {
            throw new IllegalArgumentException("diagnostic capacity and batch size must be positive");
        }
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.batchSize = batchSize;
        this.flushInterval = Objects.requireNonNull(flushInterval);
        this.closeTimeout = Objects.requireNonNull(closeTimeout);
        this.worker = new Thread(this::run, "ai-diagnostic-writer");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public boolean offer(CompletedCallDiagnostic diagnostic) {
        Objects.requireNonNull(diagnostic);
        return offer(store -> store.write(diagnostic));
    }

    @Override
    public boolean offer(BreakerTransitionDiagnostic transition) {
        Objects.requireNonNull(transition);
        return offer(store -> store.write(transition));
    }

    private boolean offer(PendingDiagnostic diagnostic) {
        if (!running.get() || !queue.offer(diagnostic)) {
            droppedCount.incrementAndGet();
            warnThrottled("模型调用诊断队列已满或正在关闭，记录已丢弃", null);
            return false;
        }
        return true;
    }

    @Override
    public long droppedCount() {
        return droppedCount.get();
    }

    @Override
    public long writeFailureCount() {
        return writeFailureCount.get();
    }

    private void run() {
        List<PendingDiagnostic> batch = new ArrayList<>(batchSize);
        while (running.get() || !queue.isEmpty()) {
            try {
                PendingDiagnostic first = queue.poll(flushInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                batch.add(first);
                queue.drainTo(batch, batchSize - 1);
                for (PendingDiagnostic diagnostic : batch) {
                    try {
                        diagnostic.writeTo(store);
                    } catch (RuntimeException failure) {
                        writeFailureCount.incrementAndGet();
                        warnThrottled("模型调用诊断写入失败，主调用结果不受影响", failure);
                    }
                }
                batch.clear();
            } catch (InterruptedException interrupted) {
                if (running.get()) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void warnThrottled(String message, Throwable failure) {
        long now = System.nanoTime();
        long last = lastWarningNanos.get();
        if ((last != 0 && now - last < WARNING_INTERVAL_NANOS)
                || !lastWarningNanos.compareAndSet(last, now)) {
            return;
        }
        if (failure == null) {
            log.warn(message);
        } else {
            log.warn("{}：{}", message, failure.getClass().getSimpleName());
        }
    }

    @Override
    @PreDestroy
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        worker.interrupt();
        try {
            worker.join(closeTimeout.toMillis());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    private interface PendingDiagnostic {

        void writeTo(DiagnosticStore store);
    }
}
