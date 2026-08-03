package com.app.ai.runtime;

public enum BreakerSettlement {
    FAILURE,
    FAILURE_OR_SLOW,
    RATE_LIMITED,
    IGNORED,
    CONFIGURATION_PROBLEM,
    ACTIVE_ATTEMPT_FAILURE,
    RELEASE_PERMISSION
}
