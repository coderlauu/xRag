package com.app.ai.runtime;

import com.app.ai.model.FailurePhase;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.TerminationReason;
import com.app.ai.provider.AttemptFailureType;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class FailureActionPolicy {

    private static final Map<AttemptFailureType, FailureAction> ACTIONS = buildActions();

    private FailureActionPolicy() {
    }

    public static FailureAction actionFor(AttemptFailureType failureType) {
        return ACTIONS.get(Objects.requireNonNull(failureType));
    }

    public static Set<AttemptFailureType> mappedFailureTypes() {
        return ACTIONS.keySet();
    }

    public static TerminationReason terminationReason(
            AttemptFailureType failureType, boolean committed) {
        Objects.requireNonNull(failureType);
        if (failureType == AttemptFailureType.CALLER_CANCELED) {
            return TerminationReason.CALLER_CANCELED;
        }
        if (committed) {
            return TerminationReason.STREAM_INTERRUPTED_AFTER_COMMIT;
        }
        if (failureType == AttemptFailureType.TOTAL_DEADLINE_EXCEEDED) {
            return TerminationReason.DEADLINE_EXCEEDED;
        }
        if (failureType == AttemptFailureType.UPSTREAM_REQUEST_REJECTED
                || failureType == AttemptFailureType.AUTHENTICATION_REJECTED
                || failureType == AttemptFailureType.PERMISSION_REJECTED) {
            return TerminationReason.NON_RECOVERABLE_ERROR;
        }
        return TerminationReason.ALL_CANDIDATES_EXHAUSTED;
    }

    private static Map<AttemptFailureType, FailureAction> buildActions() {
        var actions = new EnumMap<AttemptFailureType, FailureAction>(AttemptFailureType.class);
        add(actions, AttemptFailureType.CONNECT_FAILED, ModelErrorType.NETWORK, FailurePhase.CONNECT,
                RetryDirective.ONCE_WITH_FULL_JITTER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE);
        add(actions, AttemptFailureType.CONNECT_TIMEOUT, ModelErrorType.TIMEOUT, FailurePhase.CONNECT,
                RetryDirective.NEVER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE_OR_SLOW);
        add(actions, AttemptFailureType.RESPONSE_HEADERS_FAILED, ModelErrorType.NETWORK,
                FailurePhase.RESPONSE_HEADERS, RetryDirective.ONCE_WITH_FULL_JITTER,
                SwitchDirective.BEFORE_COMMIT, BreakerSettlement.FAILURE);
        add(actions, AttemptFailureType.RESPONSE_HEADERS_TIMEOUT, ModelErrorType.TIMEOUT,
                FailurePhase.RESPONSE_HEADERS, RetryDirective.NEVER,
                SwitchDirective.BEFORE_COMMIT, BreakerSettlement.FAILURE_OR_SLOW);
        add(actions, AttemptFailureType.RESPONSE_BODY_FAILED, ModelErrorType.NETWORK,
                FailurePhase.UPSTREAM_RESPONSE, RetryDirective.ONCE_WITH_FULL_JITTER,
                SwitchDirective.BEFORE_COMMIT, BreakerSettlement.FAILURE);
        add(actions, AttemptFailureType.RESPONSE_BODY_TIMEOUT, ModelErrorType.TIMEOUT,
                FailurePhase.UPSTREAM_RESPONSE, RetryDirective.NEVER,
                SwitchDirective.BEFORE_COMMIT, BreakerSettlement.FAILURE_OR_SLOW);
        add(actions, AttemptFailureType.SERVER_ERROR_RESPONSE, ModelErrorType.SERVER_ERROR,
                FailurePhase.RESPONSE_HEADERS, RetryDirective.ONCE_WITH_FULL_JITTER,
                SwitchDirective.BEFORE_COMMIT, BreakerSettlement.FAILURE);
        add(actions, AttemptFailureType.RATE_LIMITED_RESPONSE, ModelErrorType.RATE_LIMITED,
                FailurePhase.RESPONSE_HEADERS, RetryDirective.ACCEPTABLE_RETRY_AFTER,
                SwitchDirective.INDEPENDENT_QUOTA_BEFORE_COMMIT, BreakerSettlement.RATE_LIMITED);
        add(actions, AttemptFailureType.UPSTREAM_REQUEST_REJECTED, ModelErrorType.INVALID_REQUEST,
                FailurePhase.RESPONSE_HEADERS, RetryDirective.NEVER, SwitchDirective.NEVER,
                BreakerSettlement.IGNORED);
        add(actions, AttemptFailureType.AUTHENTICATION_REJECTED, ModelErrorType.AUTHENTICATION,
                FailurePhase.RESPONSE_HEADERS, RetryDirective.NEVER, SwitchDirective.NEVER,
                BreakerSettlement.CONFIGURATION_PROBLEM);
        add(actions, AttemptFailureType.PERMISSION_REJECTED, ModelErrorType.PERMISSION,
                FailurePhase.RESPONSE_HEADERS, RetryDirective.NEVER, SwitchDirective.NEVER,
                BreakerSettlement.CONFIGURATION_PROBLEM);
        add(actions, AttemptFailureType.FIRST_UPSTREAM_EVENT_TIMEOUT, ModelErrorType.TIMEOUT,
                FailurePhase.FIRST_UPSTREAM_EVENT, RetryDirective.NEVER,
                SwitchDirective.BEFORE_COMMIT, BreakerSettlement.FAILURE_OR_SLOW);
        add(actions, AttemptFailureType.FIRST_MEANINGFUL_CHUNK_TIMEOUT, ModelErrorType.TIMEOUT,
                FailurePhase.FIRST_MEANINGFUL_CHUNK, RetryDirective.NEVER,
                SwitchDirective.BEFORE_COMMIT, BreakerSettlement.FAILURE_OR_SLOW);
        add(actions, AttemptFailureType.UPSTREAM_RESET_BEFORE_FIRST_EVENT, ModelErrorType.NETWORK,
                FailurePhase.FIRST_UPSTREAM_EVENT, RetryDirective.NEVER,
                SwitchDirective.BEFORE_COMMIT, BreakerSettlement.FAILURE);
        add(actions, AttemptFailureType.UPSTREAM_RESET_DURING_PROBE, ModelErrorType.NETWORK,
                FailurePhase.FIRST_MEANINGFUL_CHUNK, RetryDirective.NEVER,
                SwitchDirective.BEFORE_COMMIT, BreakerSettlement.FAILURE);
        add(actions, AttemptFailureType.UPSTREAM_ERROR_EVENT, ModelErrorType.SERVER_ERROR,
                FailurePhase.UPSTREAM_STREAM, RetryDirective.NEVER,
                SwitchDirective.BEFORE_COMMIT, BreakerSettlement.FAILURE);
        add(actions, AttemptFailureType.PROVIDER_RESPONSE_INVALID, ModelErrorType.PROTOCOL_CONTRACT,
                FailurePhase.PROVIDER_RESPONSE_VALIDATION, RetryDirective.NEVER,
                SwitchDirective.BEFORE_COMMIT, BreakerSettlement.FAILURE);
        add(actions, AttemptFailureType.STREAM_IDLE_TIMEOUT, ModelErrorType.TIMEOUT,
                FailurePhase.STREAM_IDLE, RetryDirective.NEVER,
                SwitchDirective.BEFORE_COMMIT, BreakerSettlement.FAILURE_OR_SLOW);
        add(actions, AttemptFailureType.UPSTREAM_STREAM_INTERRUPTED, ModelErrorType.STREAM_INTERRUPTED,
                FailurePhase.UPSTREAM_STREAM, RetryDirective.NEVER,
                SwitchDirective.BEFORE_COMMIT, BreakerSettlement.FAILURE);
        add(actions, AttemptFailureType.TOTAL_DEADLINE_EXCEEDED, ModelErrorType.TIMEOUT,
                FailurePhase.TOTAL_DEADLINE, RetryDirective.NEVER, SwitchDirective.NEVER,
                BreakerSettlement.ACTIVE_ATTEMPT_FAILURE);
        add(actions, AttemptFailureType.CALLER_CANCELED, ModelErrorType.CANCELED, null,
                RetryDirective.NEVER, SwitchDirective.NEVER, BreakerSettlement.RELEASE_PERMISSION);
        if (actions.size() != AttemptFailureType.values().length) {
            throw new IllegalStateException("Every AttemptFailureType must have exactly one action");
        }
        return Map.copyOf(actions);
    }

    private static void add(
            EnumMap<AttemptFailureType, FailureAction> actions,
            AttemptFailureType type,
            ModelErrorType errorType,
            FailurePhase failurePhase,
            RetryDirective retry,
            SwitchDirective candidateSwitch,
            BreakerSettlement breakerSettlement) {
        if (actions.put(type, new FailureAction(
                errorType, failurePhase, retry, candidateSwitch, breakerSettlement)) != null) {
            throw new IllegalStateException("Duplicate failure action: " + type);
        }
    }
}
