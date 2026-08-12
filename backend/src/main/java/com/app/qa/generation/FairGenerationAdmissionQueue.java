package com.app.qa.generation;

import com.app.ai.model.CancellationRegistration;
import com.app.ai.model.CancellationToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

final class FairGenerationAdmissionQueue {

    enum Admission {
        STARTED,
        QUEUED,
        FULL,
        CANCELLED
    }

    interface Listener {
        void onGranted(Permit permit);

        void onTimedOut();

        void onCapacityMismatch();
    }

    interface DeadlineScheduler {
        CancellationRegistration schedule(Instant deadline, Runnable action);

        static DeadlineScheduler using(ScheduledExecutorService executor, Clock clock) {
            return (deadline, action) -> {
                long delayNanos = Math.max(0, Duration.between(clock.instant(), deadline).toNanos());
                var future = executor.schedule(action, delayNanos, TimeUnit.NANOSECONDS);
                return () -> future.cancel(false);
            };
        }
    }

    record Snapshot(int active, int queued) {}

    private final int maxConcurrency;
    private final int queueCapacity;
    private final Duration queueWait;
    private final Clock clock;
    private final DeadlineScheduler scheduler;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final ArrayDeque<Entry> queue = new ArrayDeque<>();
    private final Map<GenerationTaskId, Entry> waiting = new HashMap<>();
    private final Set<GenerationTaskId> activeTasks = new HashSet<>();

    private int active;
    private int queued;

    FairGenerationAdmissionQueue(
            int maxConcurrency,
            int queueCapacity,
            Duration queueWait,
            Clock clock,
            DeadlineScheduler scheduler) {
        if (maxConcurrency < 1 || queueCapacity < 1) {
            throw new IllegalArgumentException("concurrency and queue capacity must be positive");
        }
        if (queueCapacity < maxConcurrency) {
            throw new IllegalArgumentException("queue capacity must be at least max concurrency");
        }
        if (queueWait == null || queueWait.isZero() || queueWait.isNegative()) {
            throw new IllegalArgumentException("queue wait must be positive");
        }
        if (clock == null || scheduler == null) {
            throw new IllegalArgumentException("clock and scheduler must not be null");
        }
        this.maxConcurrency = maxConcurrency;
        this.queueCapacity = queueCapacity;
        this.queueWait = queueWait;
        this.clock = clock;
        this.scheduler = scheduler;
    }

    Admission admit(GenerationTaskId taskId, CancellationToken cancellation, Listener listener) {
        if (taskId == null || cancellation == null || listener == null) {
            throw new IllegalArgumentException("task id, cancellation and listener must not be null");
        }

        Permit immediatePermit = null;
        Entry entry = null;
        lock.lock();
        try {
            if (activeTasks.contains(taskId) || waiting.containsKey(taskId)) {
                throw new IllegalArgumentException("task is already admitted: " + taskId.value());
            }
            if (cancellation.isCancelled()) {
                return Admission.CANCELLED;
            }
            if (active < maxConcurrency) {
                active++;
                activeTasks.add(taskId);
                immediatePermit = new Permit(this, taskId);
            } else if (queued >= queueCapacity) {
                return Admission.FULL;
            } else {
                entry = new Entry(taskId, cancellation, listener, clock.instant().plus(queueWait));
                queue.addLast(entry);
                waiting.put(taskId, entry);
                queued++;
            }
        } finally {
            lock.unlock();
        }

        if (immediatePermit != null) {
            dispatchGrant(listener, immediatePermit);
            return Admission.STARTED;
        }
        register(entry);
        return Admission.QUEUED;
    }

    boolean cancel(GenerationTaskId taskId) {
        Entry entry;
        lock.lock();
        try {
            entry = waiting.remove(taskId);
            if (entry == null || entry.state != EntryState.QUEUED) {
                return false;
            }
            entry.state = EntryState.CANCELLED;
            queued--;
            entry.closeRegistrations();
        } finally {
            lock.unlock();
        }
        return true;
    }

    Snapshot snapshot() {
        lock.lock();
        try {
            return new Snapshot(active, queued);
        } finally {
            lock.unlock();
        }
    }

    private void register(Entry entry) {
        var deadlineRegistration = scheduler.schedule(entry.deadline, () -> timeout(entry.taskId));
        attachRegistration(entry, deadlineRegistration, true);
        var cancellationRegistration = entry.cancellation.onCancel(() -> cancel(entry.taskId));
        attachRegistration(entry, cancellationRegistration, false);
    }

    private void attachRegistration(Entry entry, CancellationRegistration registration, boolean deadline) {
        lock.lock();
        try {
            if (entry.state == EntryState.QUEUED) {
                if (deadline) {
                    entry.deadlineRegistration = registration;
                } else {
                    entry.cancellationRegistration = registration;
                }
            } else {
                registration.close();
            }
        } finally {
            lock.unlock();
        }
    }

    private void timeout(GenerationTaskId taskId) {
        Listener listener;
        lock.lock();
        try {
            var entry = waiting.get(taskId);
            if (entry == null || entry.state != EntryState.QUEUED || entry.deadline.isAfter(clock.instant())) {
                return;
            }
            waiting.remove(taskId);
            entry.state = EntryState.TIMED_OUT;
            queued--;
            entry.closeRegistrations();
            listener = entry.listener;
        } finally {
            lock.unlock();
        }
        listener.onTimedOut();
    }

    private void release(GenerationTaskId taskId) {
        var timedOut = new ArrayList<Listener>();
        Grant grant = null;
        lock.lock();
        try {
            if (!activeTasks.remove(taskId)) {
                return;
            }
            active--;

            while (!queue.isEmpty()) {
                var entry = queue.removeFirst();
                if (entry.state != EntryState.QUEUED) {
                    continue;
                }
                waiting.remove(entry.taskId);
                if (entry.cancellation.isCancelled()) {
                    entry.state = EntryState.CANCELLED;
                    queued--;
                    entry.closeRegistrations();
                    continue;
                }
                if (!entry.deadline.isAfter(clock.instant())) {
                    entry.state = EntryState.TIMED_OUT;
                    queued--;
                    entry.closeRegistrations();
                    timedOut.add(entry.listener);
                    continue;
                }

                entry.state = EntryState.GRANTED;
                queued--;
                entry.closeRegistrations();
                active++;
                activeTasks.add(entry.taskId);
                grant = new Grant(entry.listener, new Permit(this, entry.taskId));
                break;
            }
        } finally {
            lock.unlock();
        }

        timedOut.forEach(Listener::onTimedOut);
        if (grant != null) {
            dispatchGrant(grant.listener, grant.permit);
        }
    }

    private void dispatchGrant(Listener listener, Permit permit) {
        try {
            listener.onGranted(permit);
        } catch (RejectedExecutionException exception) {
            try {
                listener.onCapacityMismatch();
            } finally {
                permit.close();
            }
        } catch (RuntimeException exception) {
            permit.close();
            throw exception;
        }
    }

    static final class Permit implements AutoCloseable {
        private final FairGenerationAdmissionQueue owner;
        private final GenerationTaskId taskId;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Permit(FairGenerationAdmissionQueue owner, GenerationTaskId taskId) {
            this.owner = owner;
            this.taskId = taskId;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.release(taskId);
            }
        }
    }

    private enum EntryState {
        QUEUED,
        GRANTED,
        CANCELLED,
        TIMED_OUT
    }

    private static final class Entry {
        private final GenerationTaskId taskId;
        private final CancellationToken cancellation;
        private final Listener listener;
        private final Instant deadline;
        private EntryState state = EntryState.QUEUED;
        private CancellationRegistration deadlineRegistration;
        private CancellationRegistration cancellationRegistration;

        private Entry(
                GenerationTaskId taskId,
                CancellationToken cancellation,
                Listener listener,
                Instant deadline) {
            this.taskId = taskId;
            this.cancellation = cancellation;
            this.listener = listener;
            this.deadline = deadline;
        }

        private void closeRegistrations() {
            if (deadlineRegistration != null) {
                deadlineRegistration.close();
                deadlineRegistration = null;
            }
            if (cancellationRegistration != null) {
                cancellationRegistration.close();
                cancellationRegistration = null;
            }
        }
    }

    private record Grant(Listener listener, Permit permit) {}
}
