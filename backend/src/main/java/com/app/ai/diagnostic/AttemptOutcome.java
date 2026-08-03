package com.app.ai.diagnostic;

public enum AttemptOutcome {
    SUCCEEDED,
    FAILED,
    RATE_LIMITED,
    TIMED_OUT,
    CANCELED,
    EMPTY_STREAM,
    STREAM_FAILED
}
