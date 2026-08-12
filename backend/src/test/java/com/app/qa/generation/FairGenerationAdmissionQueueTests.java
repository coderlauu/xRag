package com.app.qa.generation;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.model.CancellationRegistration;
import com.app.ai.model.CancellationToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/** 3C-QUE-01~08：在公平许可 seam 验证，不依赖真实线程等待。 */
class FairGenerationAdmissionQueueTests {

    @Test
    void que01和02_许可耗尽后FIFO排队且释放只唤醒最早队首() {
        var fixture = fixture(1, 4);
        var first = fixture.listener("first");
        var second = fixture.listener("second");
        var third = fixture.listener("third");

        assertThat(fixture.admit(first)).isEqualTo(FairGenerationAdmissionQueue.Admission.STARTED);
        assertThat(fixture.admit(second)).isEqualTo(FairGenerationAdmissionQueue.Admission.QUEUED);
        assertThat(fixture.admit(third)).isEqualTo(FairGenerationAdmissionQueue.Admission.QUEUED);
        assertThat(fixture.started).containsExactly("first");

        fixture.permit("first").close();
        assertThat(fixture.started).containsExactly("first", "second");
        assertThat(fixture.queue.snapshot()).extracting("active", "queued").containsExactly(1, 1);
    }

    @Test
    void que03_取消的队首被惰性跳过并继续唤醒下一项() {
        var fixture = fixture(1, 4);
        var first = fixture.listener("first");
        var cancelled = fixture.listener("cancelled");
        var next = fixture.listener("next");
        fixture.admit(first);
        fixture.admit(cancelled);
        fixture.admit(next);

        assertThat(fixture.queue.cancel(cancelled.taskId)).isTrue();
        fixture.permit("first").close();

        assertThat(fixture.started).containsExactly("first", "next");
        assertThat(fixture.timedOut).isEmpty();
    }

    @Test
    void que04_绝对等待Deadline到达后超时且永不启动() {
        var fixture = fixture(1, 4);
        var first = fixture.listener("first");
        var waiting = fixture.listener("waiting");
        fixture.admit(first);
        fixture.admit(waiting);

        fixture.advance(Duration.ofSeconds(30));
        fixture.permit("first").close();

        assertThat(fixture.timedOut).containsExactly("waiting");
        assertThat(fixture.started).containsExactly("first");
        assertThat(fixture.queue.snapshot()).extracting("active", "queued").containsExactly(0, 0);
    }

    @Test
    void que05_超时与许可竞争只产生一个结果() {
        var timeoutFirst = fixture(1, 2);
        var holder = timeoutFirst.listener("holder");
        var waiter = timeoutFirst.listener("waiter");
        timeoutFirst.admit(holder);
        timeoutFirst.admit(waiter);
        timeoutFirst.clock.advance(Duration.ofSeconds(30));
        timeoutFirst.permit("holder").close();
        timeoutFirst.scheduler.runDue();
        assertThat(timeoutFirst.timedOut).containsExactly("waiter");
        assertThat(timeoutFirst.started).containsExactly("holder");

        var permitFirst = fixture(1, 2);
        holder = permitFirst.listener("holder");
        waiter = permitFirst.listener("waiter");
        permitFirst.admit(holder);
        permitFirst.admit(waiter);
        permitFirst.permit("holder").close();
        permitFirst.advance(Duration.ofSeconds(30));
        assertThat(permitFirst.started).containsExactly("holder", "waiter");
        assertThat(permitFirst.timedOut).isEmpty();
    }

    @Test
    void que06_队列满在任务启动前拒绝() {
        var fixture = fixture(1, 1);
        fixture.admit(fixture.listener("active"));
        fixture.admit(fixture.listener("queued"));

        assertThat(fixture.admit(fixture.listener("rejected")))
                .isEqualTo(FairGenerationAdmissionQueue.Admission.FULL);
        assertThat(fixture.started).containsExactly("active");
        assertThat(fixture.queue.snapshot()).extracting("active", "queued").containsExactly(1, 1);
    }

    @Test
    void que07_执行器拒绝映射容量错配并归还许可() {
        var fixture = fixture(1, 2);
        var rejected = fixture.listener("rejected");
        rejected.rejectGrant = true;

        assertThat(fixture.admit(rejected)).isEqualTo(FairGenerationAdmissionQueue.Admission.STARTED);

        assertThat(fixture.capacityMismatch).containsExactly("rejected");
        assertThat(fixture.queue.snapshot()).extracting("active", "queued").containsExactly(0, 0);
        assertThat(fixture.admit(fixture.listener("next")))
                .isEqualTo(FairGenerationAdmissionQueue.Admission.STARTED);
    }

    @Test
    void que08_两百任务混合取消完成后顺序稳定且许可回基线() {
        var fixture = fixture(4, 256);
        var listeners = new ArrayList<TestListener>();
        for (int index = 0; index < 200; index++) {
            var listener = fixture.listener("task-" + index);
            listeners.add(listener);
            fixture.admit(listener);
        }
        for (int index = 4; index < listeners.size(); index += 3) {
            fixture.queue.cancel(listeners.get(index).taskId);
        }
        while (fixture.queue.snapshot().active() > 0) {
            var active = List.copyOf(fixture.permits.values());
            active.forEach(FairGenerationAdmissionQueue.Permit::close);
        }

        var expected = listeners.stream()
                .filter(listener -> !listener.name.equals("task-0")
                        && !listener.name.equals("task-1")
                        && !listener.name.equals("task-2")
                        && !listener.name.equals("task-3"))
                .filter(listener -> (Integer.parseInt(listener.name.substring(5)) - 4) % 3 != 0)
                .map(listener -> listener.name)
                .toList();
        assertThat(fixture.started.subList(4, fixture.started.size())).containsExactlyElementsOf(expected);
        assertThat(fixture.queue.snapshot()).extracting("active", "queued").containsExactly(0, 0);
    }

    private static Fixture fixture(int permits, int capacity) {
        return new Fixture(permits, capacity);
    }

    private static final class Fixture {
        private final MutableClock clock = new MutableClock(Instant.parse("2026-08-12T00:00:00Z"));
        private final ManualDeadlineScheduler scheduler = new ManualDeadlineScheduler(clock);
        private final List<String> started = new ArrayList<>();
        private final List<String> timedOut = new ArrayList<>();
        private final List<String> capacityMismatch = new ArrayList<>();
        private final java.util.Map<String, FairGenerationAdmissionQueue.Permit> permits = new java.util.HashMap<>();
        private final FairGenerationAdmissionQueue queue;

        private Fixture(int permits, int capacity) {
            queue = new FairGenerationAdmissionQueue(
                    permits, capacity, Duration.ofSeconds(30), clock, scheduler);
        }

        private TestListener listener(String name) {
            return new TestListener(this, name);
        }

        private FairGenerationAdmissionQueue.Admission admit(TestListener listener) {
            return queue.admit(listener.taskId, CancellationToken.none(), listener);
        }

        private FairGenerationAdmissionQueue.Permit permit(String name) {
            return permits.get(name);
        }

        private void advance(Duration duration) {
            clock.advance(duration);
            scheduler.runDue();
        }
    }

    private static final class TestListener implements FairGenerationAdmissionQueue.Listener {
        private final Fixture fixture;
        private final String name;
        private final GenerationTaskId taskId = new GenerationTaskId(UUID.randomUUID());
        private boolean rejectGrant;

        private TestListener(Fixture fixture, String name) {
            this.fixture = fixture;
            this.name = name;
        }

        @Override
        public void onGranted(FairGenerationAdmissionQueue.Permit permit) {
            if (rejectGrant) {
                throw new RejectedExecutionException("synthetic rejection");
            }
            fixture.started.add(name);
            fixture.permits.put(name, permit);
        }

        @Override
        public void onTimedOut() {
            fixture.timedOut.add(name);
        }

        @Override
        public void onCapacityMismatch() {
            fixture.capacityMismatch.add(name);
        }
    }

    private static final class ManualDeadlineScheduler implements FairGenerationAdmissionQueue.DeadlineScheduler {
        private final MutableClock clock;
        private final List<Scheduled> scheduled = new ArrayList<>();

        private ManualDeadlineScheduler(MutableClock clock) {
            this.clock = clock;
        }

        @Override
        public CancellationRegistration schedule(Instant deadline, Runnable action) {
            var scheduledAction = new Scheduled(deadline, action);
            scheduled.add(scheduledAction);
            return () -> scheduledAction.cancelled = true;
        }

        private void runDue() {
            scheduled.stream()
                    .sorted(Comparator.comparing(scheduledAction -> scheduledAction.deadline))
                    .filter(scheduledAction -> !scheduledAction.cancelled)
                    .filter(scheduledAction -> !scheduledAction.deadline.isAfter(clock.instant()))
                    .toList()
                    .forEach(scheduledAction -> {
                        scheduledAction.cancelled = true;
                        scheduledAction.action.run();
                    });
        }

        private static final class Scheduled {
            private final Instant deadline;
            private final Runnable action;
            private boolean cancelled;

            private Scheduled(Instant deadline, Runnable action) {
                this.deadline = deadline;
                this.action = action;
            }
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
