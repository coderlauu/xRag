package com.app.ai.runtime;

import com.app.ai.model.CancellationToken;
import com.app.ai.model.DeploymentId;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public final class AttemptBudget {

    private final Deadline deadline;
    private final int maxAttemptsPerWorkUnit;
    private final int maxSameDeploymentAttempts;
    private final Map<Integer, WorkUnitCounter> counters = new HashMap<>();
    private int logicalAttemptCount;

    public AttemptBudget(
            Deadline deadline, int maxAttemptsPerWorkUnit, int maxSameDeploymentAttempts) {
        this.deadline = Objects.requireNonNull(deadline);
        if (maxAttemptsPerWorkUnit < 1 || maxSameDeploymentAttempts < 1) {
            throw new IllegalArgumentException("attempt limits must be positive");
        }
        this.maxAttemptsPerWorkUnit = maxAttemptsPerWorkUnit;
        this.maxSameDeploymentAttempts = maxSameDeploymentAttempts;
    }

    public synchronized AttemptAdmission tryStart(
            int workUnitIndex,
            DeploymentId deploymentId,
            Duration minimumRequestBudget,
            CancellationToken cancellationToken) {
        if (workUnitIndex < 0) {
            throw new IllegalArgumentException("workUnitIndex must not be negative");
        }
        Objects.requireNonNull(deploymentId);
        requirePositive(minimumRequestBudget);
        Objects.requireNonNull(cancellationToken);
        if (cancellationToken.isCancelled()) {
            return AttemptAdmission.rejected(AttemptAdmissionStatus.CANCELED);
        }
        if (Thread.currentThread().isInterrupted()) {
            return AttemptAdmission.rejected(AttemptAdmissionStatus.INTERRUPTED);
        }
        if (deadline.remaining().compareTo(minimumRequestBudget) < 0) {
            return AttemptAdmission.rejected(AttemptAdmissionStatus.BUDGET_INSUFFICIENT);
        }

        WorkUnitCounter counter = counters.computeIfAbsent(workUnitIndex, ignored -> new WorkUnitCounter());
        if (counter.attemptCount >= maxAttemptsPerWorkUnit) {
            return AttemptAdmission.rejected(AttemptAdmissionStatus.WORK_UNIT_ATTEMPTS_EXHAUSTED);
        }
        int consecutive = deploymentId.equals(counter.lastDeployment)
                ? counter.consecutiveSameDeployment
                : 0;
        if (consecutive >= maxSameDeploymentAttempts) {
            return AttemptAdmission.rejected(AttemptAdmissionStatus.SAME_DEPLOYMENT_ATTEMPTS_EXHAUSTED);
        }

        logicalAttemptCount++;
        counter.attemptCount++;
        counter.lastDeployment = deploymentId;
        counter.consecutiveSameDeployment = consecutive + 1;
        return AttemptAdmission.admitted(
                logicalAttemptCount, counter.attemptCount, counter.consecutiveSameDeployment);
    }

    public Deadline deadline() {
        return deadline;
    }

    public synchronized int logicalAttemptCount() {
        return logicalAttemptCount;
    }

    private static void requirePositive(Duration duration) {
        Objects.requireNonNull(duration);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("minimumRequestBudget must be positive");
        }
    }

    private static final class WorkUnitCounter {
        private int attemptCount;
        private DeploymentId lastDeployment;
        private int consecutiveSameDeployment;
    }
}
