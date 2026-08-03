package com.app.ai.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.breaker.BreakerKey;
import com.app.ai.breaker.BreakerTransitionReason;
import com.app.ai.model.Capability;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ProviderId;
import com.app.ai.model.TerminationReason;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class AsyncDiagnosticWriterTests {

    @Test
    void dia07_队列满时offer立即失败并递增丢弃计数() throws InterruptedException {
        var enteredStore = new CountDownLatch(1);
        var releaseStore = new CountDownLatch(1);
        DiagnosticStore blockingStore = diagnostic -> {
            enteredStore.countDown();
            awaitUninterruptibly(releaseStore);
        };
        var writer = new AsyncDiagnosticWriter(
                blockingStore, 1, 1, Duration.ofSeconds(1), Duration.ofMillis(100));

        assertThat(writer.offer(attemptZeroCall(1))).isTrue();
        assertThat(enteredStore.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(writer.offer(attemptZeroCall(2))).isTrue();
        long started = System.nanoTime();

        assertThat(writer.offer(attemptZeroCall(3))).isFalse();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(50));
        assertThat(writer.droppedCount()).isEqualTo(1);
        releaseStore.countDown();
        writer.close();
    }

    @Test
    void dia08_存储失败只递增计数且不从offer回传() {
        var writer = new AsyncDiagnosticWriter(
                diagnostic -> {
                    throw new IllegalStateException("database unavailable");
                },
                10,
                1,
                Duration.ofMillis(10),
                Duration.ofSeconds(1));

        assertThat(writer.offer(attemptZeroCall(4))).isTrue();
        writer.close();

        assertThat(writer.writeFailureCount()).isEqualTo(1);
    }

    @Test
    void dia17_关闭时存储阻塞也不会超过flush上限() throws InterruptedException {
        var enteredStore = new CountDownLatch(1);
        var releaseStore = new CountDownLatch(1);
        var writer = new AsyncDiagnosticWriter(
                diagnostic -> {
                    enteredStore.countDown();
                    awaitUninterruptibly(releaseStore);
                },
                10,
                1,
                Duration.ofMillis(10),
                Duration.ofMillis(50));
        writer.offer(attemptZeroCall(5));
        assertThat(enteredStore.await(1, TimeUnit.SECONDS)).isTrue();
        long started = System.nanoTime();

        writer.close();

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(200));
        releaseStore.countDown();
    }

    @Test
    void cbr13_transition与调用诊断共用异步队列() throws InterruptedException {
        var enteredStore = new CountDownLatch(1);
        var releaseStore = new CountDownLatch(1);
        var stored = new AtomicReference<BreakerTransitionDiagnostic>();
        DiagnosticStore store = new DiagnosticStore() {
            @Override
            public void write(CompletedCallDiagnostic diagnostic) {}

            @Override
            public void write(BreakerTransitionDiagnostic transition) {
                stored.set(transition);
                enteredStore.countDown();
                awaitUninterruptibly(releaseStore);
            }
        };
        var writer = new AsyncDiagnosticWriter(
                store, 10, 1, Duration.ofMillis(10), Duration.ofSeconds(1));
        BreakerTransitionDiagnostic transition = transition();

        assertThat(writer.offer(transition)).isTrue();
        assertThat(enteredStore.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(stored.get()).isSameAs(transition);

        releaseStore.countDown();
        writer.close();
    }

    private static CompletedCallDiagnostic attemptZeroCall(long suffix) {
        OffsetDateTime now = OffsetDateTime.parse("2026-08-03T00:00:00Z");
        return new CompletedCallDiagnostic(
                new UUID(0, suffix),
                "request-" + suffix,
                Capability.CHAT,
                null,
                null,
                false,
                "phase2-v1",
                "b".repeat(64),
                CallOutcome.FAILED,
                ModelErrorType.NO_ELIGIBLE_MODEL,
                null,
                TerminationReason.NO_ELIGIBLE_MODEL,
                null,
                null,
                false,
                null,
                null,
                new UsageDiagnostic(null, null, null, null, null, null, null, null),
                new TimingDiagnostic(0L, null, null, null, 0, now, now),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static BreakerTransitionDiagnostic transition() {
        var target = new ExecutionTarget(
                new ProviderId("provider-a"),
                new DeploymentId("chat-a"),
                new ModelIdentity("chat-v1"));
        return new BreakerTransitionDiagnostic(
                new BreakerKey(target.providerId(), target.deploymentId(), Capability.CHAT),
                target,
                BreakerState.CLOSED,
                BreakerState.OPEN,
                BreakerTransitionReason.FAILURE_RATE,
                OffsetDateTime.parse("2026-08-03T00:00:00Z"));
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException ignored) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
