package com.app.ai.breaker;

public enum BreakerTransitionReason {
    FAILURE_RATE,
    SLOW_CALL_RATE,
    WAIT_ELAPSED,
    HALF_OPEN_SUCCESS,
    HALF_OPEN_FAILURE
}
