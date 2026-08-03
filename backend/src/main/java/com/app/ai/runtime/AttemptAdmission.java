package com.app.ai.runtime;

public record AttemptAdmission(
        AttemptAdmissionStatus status,
        Integer logicalAttemptNo,
        Integer workUnitAttemptNo,
        Integer sameDeploymentAttemptNo) {

    static AttemptAdmission rejected(AttemptAdmissionStatus status) {
        return new AttemptAdmission(status, null, null, null);
    }

    static AttemptAdmission admitted(int logical, int workUnit, int sameDeployment) {
        return new AttemptAdmission(
                AttemptAdmissionStatus.ADMITTED, logical, workUnit, sameDeployment);
    }
}
