package com.app.ai.diagnostic;

public interface DiagnosticStore {

    void write(CompletedCallDiagnostic diagnostic);

    default void write(BreakerTransitionDiagnostic transition) {}
}
