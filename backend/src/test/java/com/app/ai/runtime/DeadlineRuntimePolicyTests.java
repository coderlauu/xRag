package com.app.ai.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.model.CancellationToken;
import com.app.ai.model.DeploymentId;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DeadlineRuntimePolicyTests {

    @Test
    void rte15和18_调用方只能缩短模块上限且预算只随单调时钟递减() {
        var tickerNanos = new AtomicLong();
        var clamped = MonotonicDeadline.start(
                Duration.ofSeconds(45), Duration.ofMinutes(2), tickerNanos::get);

        assertThat(clamped.remaining()).isEqualTo(Duration.ofSeconds(45));
        tickerNanos.addAndGet(Duration.ofSeconds(5).toNanos());
        assertThat(clamped.remaining()).isEqualTo(Duration.ofSeconds(40));

        var shortened = MonotonicDeadline.start(
                Duration.ofSeconds(45), Duration.ofSeconds(12), tickerNanos::get);
        assertThat(shortened.remaining()).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void rte14_阶段timeout取阶段上限与剩余预算较小值且预算不足时拒绝attempt() {
        Deadline oneSecondLeft = () -> Duration.ofSeconds(1);

        assertThat(PhaseTimeoutPolicy.select(
                oneSecondLeft, Duration.ofSeconds(3), Duration.ofMillis(200)))
                .contains(new PhaseTimeout(Duration.ofSeconds(1), TimeoutSource.TOTAL_DEADLINE));
        assertThat(PhaseTimeoutPolicy.select(
                oneSecondLeft, Duration.ofMillis(300), Duration.ofMillis(200)))
                .contains(new PhaseTimeout(Duration.ofMillis(300), TimeoutSource.PHASE_LIMIT));
        assertThat(PhaseTimeoutPolicy.select(
                () -> Duration.ofMillis(199), Duration.ofSeconds(3), Duration.ofMillis(200)))
                .isEmpty();
    }

    @Test
    void rte16和19_每个workUnit独立计数但共享同一个总Deadline() {
        Deadline deadline = () -> Duration.ofMinutes(10);
        var budget = new AttemptBudget(deadline, 3, 2);
        var deployment = new DeploymentId("embedding-a");

        for (int workUnit = 0; workUnit < 70; workUnit++) {
            assertThat(budget.tryStart(
                    workUnit, deployment, Duration.ofMillis(200), CancellationToken.none()).status())
                    .isEqualTo(AttemptAdmissionStatus.ADMITTED);
        }

        assertThat(budget.logicalAttemptCount()).isEqualTo(70);
        assertThat(budget.deadline()).isSameAs(deadline);
    }

    @Test
    void rte14到16_预算和每批上限在发请求前给出稳定拒绝原因() {
        var deploymentA = new DeploymentId("chat-a");
        var deploymentB = new DeploymentId("chat-b");
        var budget = new AttemptBudget(() -> Duration.ofSeconds(1), 3, 2);

        assertThat(budget.tryStart(0, deploymentA, Duration.ofMillis(200), CancellationToken.none()).status())
                .isEqualTo(AttemptAdmissionStatus.ADMITTED);
        assertThat(budget.tryStart(0, deploymentA, Duration.ofMillis(200), CancellationToken.none()).status())
                .isEqualTo(AttemptAdmissionStatus.ADMITTED);
        assertThat(budget.tryStart(0, deploymentA, Duration.ofMillis(200), CancellationToken.none()).status())
                .isEqualTo(AttemptAdmissionStatus.SAME_DEPLOYMENT_ATTEMPTS_EXHAUSTED);
        assertThat(budget.tryStart(0, deploymentB, Duration.ofMillis(200), CancellationToken.none()).status())
                .isEqualTo(AttemptAdmissionStatus.ADMITTED);
        assertThat(budget.tryStart(0, deploymentA, Duration.ofMillis(200), CancellationToken.none()).status())
                .isEqualTo(AttemptAdmissionStatus.WORK_UNIT_ATTEMPTS_EXHAUSTED);

        var insufficient = new AttemptBudget(() -> Duration.ofMillis(199), 3, 2);
        assertThat(insufficient.tryStart(
                0, deploymentA, Duration.ofMillis(200), CancellationToken.none()).status())
                .isEqualTo(AttemptAdmissionStatus.BUDGET_INSUFFICIENT);
        assertThat(insufficient.logicalAttemptCount()).isZero();
    }

    @Test
    void syn14和15_取消或线程中断不消耗attempt且中断标志保持() {
        var budget = new AttemptBudget(() -> Duration.ofSeconds(1), 3, 2);
        var deployment = new DeploymentId("chat-a");
        CancellationToken canceled = new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public com.app.ai.model.CancellationRegistration onCancel(Runnable listener) {
                return () -> { };
            }
        };

        assertThat(budget.tryStart(0, deployment, Duration.ofMillis(200), canceled).status())
                .isEqualTo(AttemptAdmissionStatus.CANCELED);
        assertThat(budget.logicalAttemptCount()).isZero();

        Thread.currentThread().interrupt();
        try {
            assertThat(budget.tryStart(
                    0, deployment, Duration.ofMillis(200), CancellationToken.none()).status())
                    .isEqualTo(AttemptAdmissionStatus.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(budget.logicalAttemptCount()).isZero();
        } finally {
            Thread.interrupted();
        }
    }
}
