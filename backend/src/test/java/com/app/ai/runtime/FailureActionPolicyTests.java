package com.app.ai.runtime;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.model.FailurePhase;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.TerminationReason;
import com.app.ai.provider.AttemptFailureType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class FailureActionPolicyTests {

    @Test
    void syn19_全部AttemptFailure都有且只有一个公开错误与失败阶段映射() {
        Map<AttemptFailureType, FailureMapping> expected = Map.ofEntries(
                entry(AttemptFailureType.CONNECT_FAILED,
                        new FailureMapping(ModelErrorType.NETWORK, FailurePhase.CONNECT)),
                entry(AttemptFailureType.CONNECT_TIMEOUT,
                        new FailureMapping(ModelErrorType.TIMEOUT, FailurePhase.CONNECT)),
                entry(AttemptFailureType.RESPONSE_HEADERS_FAILED,
                        new FailureMapping(ModelErrorType.NETWORK, FailurePhase.RESPONSE_HEADERS)),
                entry(AttemptFailureType.RESPONSE_HEADERS_TIMEOUT,
                        new FailureMapping(ModelErrorType.TIMEOUT, FailurePhase.RESPONSE_HEADERS)),
                entry(AttemptFailureType.RESPONSE_BODY_FAILED,
                        new FailureMapping(ModelErrorType.NETWORK, FailurePhase.UPSTREAM_RESPONSE)),
                entry(AttemptFailureType.RESPONSE_BODY_TIMEOUT,
                        new FailureMapping(ModelErrorType.TIMEOUT, FailurePhase.UPSTREAM_RESPONSE)),
                entry(AttemptFailureType.SERVER_ERROR_RESPONSE,
                        new FailureMapping(ModelErrorType.SERVER_ERROR, FailurePhase.RESPONSE_HEADERS)),
                entry(AttemptFailureType.RATE_LIMITED_RESPONSE,
                        new FailureMapping(ModelErrorType.RATE_LIMITED, FailurePhase.RESPONSE_HEADERS)),
                entry(AttemptFailureType.UPSTREAM_REQUEST_REJECTED,
                        new FailureMapping(ModelErrorType.INVALID_REQUEST, FailurePhase.RESPONSE_HEADERS)),
                entry(AttemptFailureType.AUTHENTICATION_REJECTED,
                        new FailureMapping(ModelErrorType.AUTHENTICATION, FailurePhase.RESPONSE_HEADERS)),
                entry(AttemptFailureType.PERMISSION_REJECTED,
                        new FailureMapping(ModelErrorType.PERMISSION, FailurePhase.RESPONSE_HEADERS)),
                entry(AttemptFailureType.FIRST_UPSTREAM_EVENT_TIMEOUT,
                        new FailureMapping(ModelErrorType.TIMEOUT, FailurePhase.FIRST_UPSTREAM_EVENT)),
                entry(AttemptFailureType.FIRST_MEANINGFUL_CHUNK_TIMEOUT,
                        new FailureMapping(ModelErrorType.TIMEOUT, FailurePhase.FIRST_MEANINGFUL_CHUNK)),
                entry(AttemptFailureType.UPSTREAM_RESET_BEFORE_FIRST_EVENT,
                        new FailureMapping(ModelErrorType.NETWORK, FailurePhase.FIRST_UPSTREAM_EVENT)),
                entry(AttemptFailureType.UPSTREAM_RESET_DURING_PROBE,
                        new FailureMapping(ModelErrorType.NETWORK, FailurePhase.FIRST_MEANINGFUL_CHUNK)),
                entry(AttemptFailureType.UPSTREAM_ERROR_EVENT,
                        new FailureMapping(ModelErrorType.SERVER_ERROR, FailurePhase.UPSTREAM_STREAM)),
                entry(AttemptFailureType.PROVIDER_RESPONSE_INVALID,
                        new FailureMapping(ModelErrorType.PROTOCOL_CONTRACT,
                                FailurePhase.PROVIDER_RESPONSE_VALIDATION)),
                entry(AttemptFailureType.STREAM_IDLE_TIMEOUT,
                        new FailureMapping(ModelErrorType.TIMEOUT, FailurePhase.STREAM_IDLE)),
                entry(AttemptFailureType.UPSTREAM_STREAM_INTERRUPTED,
                        new FailureMapping(ModelErrorType.STREAM_INTERRUPTED, FailurePhase.UPSTREAM_STREAM)),
                entry(AttemptFailureType.TOTAL_DEADLINE_EXCEEDED,
                        new FailureMapping(ModelErrorType.TIMEOUT, FailurePhase.TOTAL_DEADLINE)),
                entry(AttemptFailureType.CALLER_CANCELED,
                        new FailureMapping(ModelErrorType.CANCELED, null)));

        assertThat(FailureActionPolicy.mappedFailureTypes())
                .containsExactlyInAnyOrder(AttemptFailureType.values());
        assertThat(AttemptFailureType.values()).allSatisfy(type -> {
            var action = FailureActionPolicy.actionFor(type);
            assertThat(new FailureMapping(action.errorType(), action.failurePhase()))
                    .isEqualTo(expected.get(type));
        });
    }

    @Test
    void syn19_全部恢复与breaker动作和架构表逐项一致() {
        assertAction(AttemptFailureType.CONNECT_FAILED,
                RetryDirective.ONCE_WITH_FULL_JITTER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE);
        assertAction(AttemptFailureType.CONNECT_TIMEOUT,
                RetryDirective.NEVER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE_OR_SLOW);
        assertAction(AttemptFailureType.RESPONSE_HEADERS_FAILED,
                RetryDirective.ONCE_WITH_FULL_JITTER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE);
        assertAction(AttemptFailureType.RESPONSE_HEADERS_TIMEOUT,
                RetryDirective.NEVER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE_OR_SLOW);
        assertAction(AttemptFailureType.RESPONSE_BODY_FAILED,
                RetryDirective.ONCE_WITH_FULL_JITTER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE);
        assertAction(AttemptFailureType.RESPONSE_BODY_TIMEOUT,
                RetryDirective.NEVER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE_OR_SLOW);
        assertAction(AttemptFailureType.SERVER_ERROR_RESPONSE,
                RetryDirective.ONCE_WITH_FULL_JITTER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE);
        assertAction(AttemptFailureType.RATE_LIMITED_RESPONSE,
                RetryDirective.ACCEPTABLE_RETRY_AFTER,
                SwitchDirective.INDEPENDENT_QUOTA_BEFORE_COMMIT,
                BreakerSettlement.RATE_LIMITED);
        assertAction(AttemptFailureType.UPSTREAM_REQUEST_REJECTED,
                RetryDirective.NEVER, SwitchDirective.NEVER, BreakerSettlement.IGNORED);
        assertAction(AttemptFailureType.AUTHENTICATION_REJECTED,
                RetryDirective.NEVER, SwitchDirective.NEVER,
                BreakerSettlement.CONFIGURATION_PROBLEM);
        assertAction(AttemptFailureType.PERMISSION_REJECTED,
                RetryDirective.NEVER, SwitchDirective.NEVER,
                BreakerSettlement.CONFIGURATION_PROBLEM);
        assertAction(AttemptFailureType.FIRST_UPSTREAM_EVENT_TIMEOUT,
                RetryDirective.NEVER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE_OR_SLOW);
        assertAction(AttemptFailureType.FIRST_MEANINGFUL_CHUNK_TIMEOUT,
                RetryDirective.NEVER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE_OR_SLOW);
        assertAction(AttemptFailureType.UPSTREAM_RESET_BEFORE_FIRST_EVENT,
                RetryDirective.NEVER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE);
        assertAction(AttemptFailureType.UPSTREAM_RESET_DURING_PROBE,
                RetryDirective.NEVER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE);
        assertAction(AttemptFailureType.UPSTREAM_ERROR_EVENT,
                RetryDirective.NEVER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE);
        assertAction(AttemptFailureType.PROVIDER_RESPONSE_INVALID,
                RetryDirective.NEVER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE);
        assertAction(AttemptFailureType.STREAM_IDLE_TIMEOUT,
                RetryDirective.NEVER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE_OR_SLOW);
        assertAction(AttemptFailureType.UPSTREAM_STREAM_INTERRUPTED,
                RetryDirective.NEVER, SwitchDirective.BEFORE_COMMIT,
                BreakerSettlement.FAILURE);
        assertAction(AttemptFailureType.TOTAL_DEADLINE_EXCEEDED,
                RetryDirective.NEVER, SwitchDirective.NEVER,
                BreakerSettlement.ACTIVE_ATTEMPT_FAILURE);
        assertAction(AttemptFailureType.CALLER_CANCELED,
                RetryDirective.NEVER, SwitchDirective.NEVER,
                BreakerSettlement.RELEASE_PERMISSION);
    }

    @Test
    void syn05到07_调用契约与鉴权权限失败不可恢复且不计breaker() {
        assertNonRecoverable(AttemptFailureType.UPSTREAM_REQUEST_REJECTED, ModelErrorType.INVALID_REQUEST);
        assertNonRecoverable(AttemptFailureType.AUTHENTICATION_REJECTED, ModelErrorType.AUTHENTICATION);
        assertNonRecoverable(AttemptFailureType.PERMISSION_REJECTED, ModelErrorType.PERMISSION);
    }

    @Test
    void syn08到12_限流只接受有预算的RetryAfter且只切独立QuotaDomain() {
        var action = FailureActionPolicy.actionFor(AttemptFailureType.RATE_LIMITED_RESPONSE);
        var clock = Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC);

        assertThat(action.sameCandidateRetry()).isEqualTo(RetryDirective.ACCEPTABLE_RETRY_AFTER);
        assertThat(action.maySwitchCandidate(false, true)).isTrue();
        assertThat(action.maySwitchCandidate(false, false)).isFalse();
        assertThat(action.maySwitchCandidate(true, true)).isFalse();
        assertThat(action.breakerSettlement()).isEqualTo(BreakerSettlement.RATE_LIMITED);
        assertThat(RetryAfterPolicy.accept("2", clock, () -> Duration.ofSeconds(4), Duration.ofSeconds(1)))
                .contains(Duration.ofSeconds(2));
        assertThat(RetryAfterPolicy.accept("6", clock, () -> Duration.ofSeconds(10), Duration.ofSeconds(1)))
                .isEmpty();
        assertThat(RetryAfterPolicy.accept("invalid", clock, () -> Duration.ofSeconds(10), Duration.ofSeconds(1)))
                .isEmpty();
        assertThat(RetryAfterPolicy.accept("2", clock, () -> Duration.ofMillis(2999), Duration.ofSeconds(1)))
                .isEmpty();
        assertThat(FailureActionPolicy.terminationReason(
                AttemptFailureType.RATE_LIMITED_RESPONSE, false))
                .isEqualTo(TerminationReason.ALL_CANDIDATES_EXHAUSTED);
    }

    @Test
    void rte19_非限流重试使用可注入fullJitter且等待消耗现有Deadline() throws Exception {
        var observedUpperBound = new AtomicReference<Duration>();
        JitterSource midpoint = upperBound -> {
            observedUpperBound.set(upperBound);
            return upperBound.dividedBy(2);
        };
        var deadline = new AtomicReference<Duration>(Duration.ofSeconds(2));
        var slept = new AtomicReference<Duration>();

        Duration delay = FullJitterBackoff.delay(1, midpoint);
        WaitOutcome outcome = DeadlineWaiter.waitFor(
                delay,
                deadline::get,
                Duration.ofMillis(200),
                duration -> {
                    slept.set(duration);
                    deadline.set(deadline.get().minus(duration));
                });

        assertThat(observedUpperBound.get()).isEqualTo(Duration.ofMillis(200));
        assertThat(delay).isEqualTo(Duration.ofMillis(100));
        assertThat(slept.get()).isEqualTo(Duration.ofMillis(100));
        assertThat(deadline.get()).isEqualTo(Duration.ofMillis(1900));
        assertThat(outcome).isEqualTo(WaitOutcome.COMPLETED);
        assertThat(FullJitterBackoff.delay(20, upperBound -> upperBound))
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void 提交状态是切换最终门且取消和总Deadline有稳定停止原因() {
        var network = FailureActionPolicy.actionFor(AttemptFailureType.CONNECT_FAILED);
        assertThat(network.sameCandidateRetry()).isEqualTo(RetryDirective.ONCE_WITH_FULL_JITTER);
        assertThat(network.maySwitchCandidate(false, false)).isTrue();
        assertThat(network.maySwitchCandidate(true, true)).isFalse();
        assertThat(FailureActionPolicy.terminationReason(
                AttemptFailureType.TOTAL_DEADLINE_EXCEEDED, false))
                .isEqualTo(TerminationReason.DEADLINE_EXCEEDED);
        assertThat(FailureActionPolicy.terminationReason(
                AttemptFailureType.TOTAL_DEADLINE_EXCEEDED, true))
                .isEqualTo(TerminationReason.STREAM_INTERRUPTED_AFTER_COMMIT);
        assertThat(FailureActionPolicy.terminationReason(
                AttemptFailureType.CALLER_CANCELED, true))
                .isEqualTo(TerminationReason.CALLER_CANCELED);
    }

    private static void assertNonRecoverable(
            AttemptFailureType failureType, ModelErrorType errorType) {
        var action = FailureActionPolicy.actionFor(failureType);
        assertThat(action.errorType()).isEqualTo(errorType);
        assertThat(action.sameCandidateRetry()).isEqualTo(RetryDirective.NEVER);
        assertThat(action.maySwitchCandidate(false, true)).isFalse();
        assertThat(action.breakerSettlement()).isIn(
                BreakerSettlement.IGNORED, BreakerSettlement.CONFIGURATION_PROBLEM);
        assertThat(FailureActionPolicy.terminationReason(failureType, false))
                .isEqualTo(TerminationReason.NON_RECOVERABLE_ERROR);
    }

    private static void assertAction(
            AttemptFailureType type,
            RetryDirective retry,
            SwitchDirective candidateSwitch,
            BreakerSettlement breakerSettlement) {
        var action = FailureActionPolicy.actionFor(type);
        assertThat(action.sameCandidateRetry()).isEqualTo(retry);
        assertThat(action.candidateSwitch()).isEqualTo(candidateSwitch);
        assertThat(action.breakerSettlement()).isEqualTo(breakerSettlement);
    }
}
