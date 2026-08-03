package com.app.ai.diagnostic;

@FunctionalInterface
public interface BreakerTransitionRecorder {

    boolean offer(BreakerTransitionDiagnostic transition);
}
