package com.app.ai.diagnostic;

public interface DiagnosticRecorder {

    boolean offer(CompletedCallDiagnostic diagnostic);

    long droppedCount();

    long writeFailureCount();
}
