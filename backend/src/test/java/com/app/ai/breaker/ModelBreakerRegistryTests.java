package com.app.ai.breaker;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.diagnostic.BreakerState;
import com.app.ai.diagnostic.BreakerTransitionDiagnostic;
import com.app.ai.model.AiRuntimePolicy;
import com.app.ai.model.Capability;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ProviderId;
import com.app.ai.provider.AttemptFailureType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ModelBreakerRegistryTests {

    private static final ExecutionTarget CHAT_TARGET = new ExecutionTarget(
            new ProviderId("provider-a"),
            new DeploymentId("chat-a"),
            new ModelIdentity("chat-v1"));

    @Test
    void cbr01和02_达到最小调用数且失败率50时从closed转open() {
        List<BreakerTransitionDiagnostic> transitions = new ArrayList<>();
        var registry = new Resilience4jModelBreakerRegistry(
                limits(),
                Clock.fixed(Instant.parse("2026-08-03T00:00:00Z"), ZoneOffset.UTC),
                transition -> {
                    transitions.add(transition);
                    return true;
                });

        fail(registry, AttemptFailureType.SERVER_ERROR_RESPONSE);
        succeed(registry);
        fail(registry, AttemptFailureType.CONNECT_FAILED);
        assertThat(registry.snapshot(CHAT_TARGET, Capability.CHAT).state())
                .isEqualTo(BreakerState.CLOSED);

        succeed(registry);

        var snapshot = registry.snapshot(CHAT_TARGET, Capability.CHAT);
        assertThat(snapshot.state()).isEqualTo(BreakerState.OPEN);
        assertThat(snapshot.bufferedCalls()).isEqualTo(4);
        assertThat(snapshot.successfulCalls()).isEqualTo(2);
        assertThat(snapshot.failedCalls()).isEqualTo(2);
        assertThat(snapshot.failureRate()).isEqualTo(50.0f);
        assertThat(snapshot.allowsCall()).isFalse();
        assertThat(snapshot.lastChangedAt())
                .isEqualTo(Instant.parse("2026-08-03T00:00:00Z").atOffset(ZoneOffset.UTC));
        assertThat(snapshot.lastChangeReason()).isEqualTo(BreakerTransitionReason.FAILURE_RATE);
        assertThat(transitions).singleElement().satisfies(transition -> {
            assertThat(transition.fromState()).isEqualTo(BreakerState.CLOSED);
            assertThat(transition.toState()).isEqualTo(BreakerState.OPEN);
            assertThat(transition.reason()).isEqualTo(BreakerTransitionReason.FAILURE_RATE);
            assertThat(transition.transitionedAt())
                    .isEqualTo(Instant.parse("2026-08-03T00:00:00Z").atOffset(ZoneOffset.UTC));
        });
    }

    @Test
    void cbr03_全部成功但慢调用率达50时以稳定原因开路() {
        List<BreakerTransitionDiagnostic> transitions = new ArrayList<>();
        var registry = new Resilience4jModelBreakerRegistry(
                limits(), Clock.systemUTC(), transition -> transitions.add(transition));

        succeed(registry, Duration.ofSeconds(16));
        succeed(registry, Duration.ofMillis(10));
        succeed(registry, Duration.ofSeconds(17));
        succeed(registry, Duration.ofMillis(10));

        var snapshot = registry.snapshot(CHAT_TARGET, Capability.CHAT);
        assertThat(snapshot.state()).isEqualTo(BreakerState.OPEN);
        assertThat(snapshot.failedCalls()).isZero();
        assertThat(snapshot.slowCalls()).isEqualTo(2);
        assertThat(snapshot.slowCallRate()).isEqualTo(50.0f);
        assertThat(transitions).singleElement()
                .satisfies(transition -> assertThat(transition.reason())
                        .isEqualTo(BreakerTransitionReason.SLOW_CALL_RATE));
    }

    @Test
    void cbr04到07_open等待到期后仅放行两个halfOpen许可并在全成功后关闭() {
        var clock = new MutableClock(Instant.parse("2026-08-03T00:00:00Z"));
        List<BreakerTransitionDiagnostic> transitions = new ArrayList<>();
        var registry = new Resilience4jModelBreakerRegistry(
                limits(), clock, transition -> transitions.add(transition));
        open(registry);

        assertThat(registry.tryAcquire(CHAT_TARGET, Capability.CHAT))
                .isInstanceOf(BreakerPermissionRejected.class)
                .extracting(BreakerAdmission::state)
                .isEqualTo(BreakerState.OPEN);
        clock.advance(Duration.ofSeconds(29));
        assertThat(registry.tryAcquire(CHAT_TARGET, Capability.CHAT))
                .isInstanceOf(BreakerPermissionRejected.class);

        clock.advance(Duration.ofSeconds(1));
        BreakerPermission first = permission(registry.tryAcquire(CHAT_TARGET, Capability.CHAT));
        BreakerPermission second = permission(registry.tryAcquire(CHAT_TARGET, Capability.CHAT));
        assertThat(first.state()).isEqualTo(BreakerState.HALF_OPEN);
        assertThat(second.state()).isEqualTo(BreakerState.HALF_OPEN);
        var rejected = (BreakerPermissionRejected)
                registry.tryAcquire(CHAT_TARGET, Capability.CHAT);
        assertThat(rejected.state()).isEqualTo(BreakerState.HALF_OPEN);
        assertThat(rejected.key())
                .isEqualTo(new BreakerKey(
                        CHAT_TARGET.providerId(), CHAT_TARGET.deploymentId(), Capability.CHAT));
        assertThat(rejected.occurredAt())
                .isEqualTo(Instant.parse("2026-08-03T00:00:30Z").atOffset(ZoneOffset.UTC));
        var occupied = registry.snapshot(CHAT_TARGET, Capability.CHAT);
        assertThat(occupied.halfOpenPermissionInUse()).isTrue();
        assertThat(occupied.allowsCall()).isFalse();

        first.succeed(Duration.ofMillis(10));
        second.succeed(Duration.ofMillis(10));

        assertThat(registry.snapshot(CHAT_TARGET, Capability.CHAT).state())
                .isEqualTo(BreakerState.CLOSED);
        assertThat(transitions).extracting(BreakerTransitionDiagnostic::reason)
                .containsExactly(
                        BreakerTransitionReason.FAILURE_RATE,
                        BreakerTransitionReason.WAIT_ELAPSED,
                        BreakerTransitionReason.HALF_OPEN_SUCCESS);
    }

    @Test
    void cbr08_halfOpen探测失败率达阈值后重新开路() {
        var clock = new MutableClock(Instant.parse("2026-08-03T00:00:00Z"));
        List<BreakerTransitionDiagnostic> transitions = new ArrayList<>();
        var registry = new Resilience4jModelBreakerRegistry(
                limits(), clock, transition -> transitions.add(transition));
        open(registry);
        clock.advance(Duration.ofSeconds(30));
        BreakerPermission first = permission(registry.tryAcquire(CHAT_TARGET, Capability.CHAT));
        BreakerPermission second = permission(registry.tryAcquire(CHAT_TARGET, Capability.CHAT));

        first.fail(Duration.ofMillis(10), AttemptFailureType.PROVIDER_RESPONSE_INVALID);
        second.succeed(Duration.ofMillis(10));

        assertThat(registry.snapshot(CHAT_TARGET, Capability.CHAT).state())
                .isEqualTo(BreakerState.OPEN);
        assertThat(transitions).extracting(BreakerTransitionDiagnostic::reason)
                .containsExactly(
                        BreakerTransitionReason.FAILURE_RATE,
                        BreakerTransitionReason.WAIT_ELAPSED,
                        BreakerTransitionReason.HALF_OPEN_FAILURE);
    }

    @Test
    void cbr09和10_不计失败的类型释放许可且限流单独计数() {
        var registry = new Resilience4jModelBreakerRegistry(
                limits(), Clock.systemUTC(), transition -> true);

        fail(registry, AttemptFailureType.UPSTREAM_REQUEST_REJECTED);
        fail(registry, AttemptFailureType.AUTHENTICATION_REJECTED);
        fail(registry, AttemptFailureType.PERMISSION_REJECTED);
        BreakerPermission canceled = permission(registry.tryAcquire(CHAT_TARGET, Capability.CHAT));
        canceled.fail(Duration.ofMillis(10), AttemptFailureType.CALLER_CANCELED);
        BreakerPermission rateLimited = permission(registry.tryAcquire(CHAT_TARGET, Capability.CHAT));
        rateLimited.fail(Duration.ofMillis(10), AttemptFailureType.RATE_LIMITED_RESPONSE);
        rateLimited.fail(Duration.ofMillis(10), AttemptFailureType.RATE_LIMITED_RESPONSE);

        var snapshot = registry.snapshot(CHAT_TARGET, Capability.CHAT);
        assertThat(snapshot.state()).isEqualTo(BreakerState.CLOSED);
        assertThat(snapshot.bufferedCalls()).isZero();
        assertThat(snapshot.failedCalls()).isZero();
        assertThat(snapshot.rateLimitedCalls()).isEqualTo(1);
    }

    @Test
    void cbr11_chat开路不影响同deployment的embedding() {
        var registry = new Resilience4jModelBreakerRegistry(
                limits(), Clock.systemUTC(), transition -> true);
        open(registry);

        var embedding = registry.snapshot(CHAT_TARGET, Capability.EMBEDDING);

        assertThat(embedding.key().deploymentId()).isEqualTo(CHAT_TARGET.deploymentId());
        assertThat(embedding.state()).isEqualTo(BreakerState.CLOSED);
        assertThat(embedding.bufferedCalls()).isZero();
        assertThat(registry.snapshots()).hasSize(2);
    }

    @Test
    void cbr12_registry重建后从closed开始并公开重启语义() {
        var original = new Resilience4jModelBreakerRegistry(
                limits(), Clock.systemUTC(), transition -> true);
        open(original);

        var rebuilt = new Resilience4jModelBreakerRegistry(
                limits(), Clock.systemUTC(), transition -> true);
        var snapshot = rebuilt.snapshot(CHAT_TARGET, Capability.CHAT);

        assertThat(snapshot.state()).isEqualTo(BreakerState.CLOSED);
        assertThat(snapshot.restartSemantics()).isEqualTo(BreakerRestartSemantics.RESET_TO_CLOSED);
        assertThat(snapshot.bufferedCalls()).isZero();
        assertThat(snapshot.allowsCall()).isTrue();
        assertThat(snapshot.halfOpenPermissionInUse()).isFalse();
        assertThat(snapshot.lastChangedAt()).isNull();
        assertThat(snapshot.lastChangeReason()).isNull();
    }

    @Test
    void cbr13_transition录制失败不影响breaker状态() {
        var registry = new Resilience4jModelBreakerRegistry(
                limits(), Clock.systemUTC(), transition -> {
                    throw new IllegalStateException("diagnostic store unavailable");
                });

        open(registry);

        assertThat(registry.snapshot(CHAT_TARGET, Capability.CHAT).state())
                .isEqualTo(BreakerState.OPEN);
    }

    @Test
    void cbr14_halfOpen取消释放许可且不记录成败() {
        var clock = new MutableClock(Instant.parse("2026-08-03T00:00:00Z"));
        var registry = new Resilience4jModelBreakerRegistry(
                limits(), clock, transition -> true);
        open(registry);
        clock.advance(Duration.ofSeconds(30));
        BreakerPermission first = permission(registry.tryAcquire(CHAT_TARGET, Capability.CHAT));
        BreakerPermission canceled = permission(registry.tryAcquire(CHAT_TARGET, Capability.CHAT));

        canceled.fail(Duration.ofMillis(10), AttemptFailureType.CALLER_CANCELED);

        assertThat(registry.snapshot(CHAT_TARGET, Capability.CHAT).allowsCall()).isTrue();
        BreakerPermission replacement = permission(registry.tryAcquire(CHAT_TARGET, Capability.CHAT));
        first.succeed(Duration.ofMillis(10));
        replacement.succeed(Duration.ofMillis(10));
        assertThat(registry.snapshot(CHAT_TARGET, Capability.CHAT).state())
                .isEqualTo(BreakerState.CLOSED);
    }

    @Test
    void cbr14_取消与终态竞态每个许可只结算一次() throws Exception {
        var registry = new Resilience4jModelBreakerRegistry(
                limits(), Clock.systemUTC(), transition -> true);
        BreakerPermission permission = permission(registry.tryAcquire(CHAT_TARGET, Capability.CHAT));
        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var success = executor.submit(() -> {
                start.await();
                permission.succeed(Duration.ofMillis(10));
                return null;
            });
            var failure = executor.submit(() -> {
                start.await();
                permission.fail(Duration.ofMillis(10), AttemptFailureType.CONNECT_FAILED);
                return null;
            });

            start.countDown();
            success.get(1, TimeUnit.SECONDS);
            failure.get(1, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        var snapshot = registry.snapshot(CHAT_TARGET, Capability.CHAT);
        assertThat(permission.settled()).isTrue();
        assertThat(snapshot.bufferedCalls()).isEqualTo(1);
        assertThat(snapshot.failedCalls()).isBetween(0, 1);
    }

    @Test
    void cbr15_provider协议失败进入普通失败率() {
        var registry = new Resilience4jModelBreakerRegistry(
                limits(), Clock.systemUTC(), transition -> true);

        fail(registry, AttemptFailureType.PROVIDER_RESPONSE_INVALID);
        succeed(registry);
        fail(registry, AttemptFailureType.PROVIDER_RESPONSE_INVALID);
        succeed(registry);

        var snapshot = registry.snapshot(CHAT_TARGET, Capability.CHAT);
        assertThat(snapshot.state()).isEqualTo(BreakerState.OPEN);
        assertThat(snapshot.failedCalls()).isEqualTo(2);
        assertThat(snapshot.failureRate()).isEqualTo(50.0f);
    }

    private static void fail(
            ModelBreakerRegistry registry, AttemptFailureType failureType) {
        permission(registry.tryAcquire(CHAT_TARGET, Capability.CHAT))
                .fail(Duration.ofMillis(10), failureType);
    }

    private static void succeed(ModelBreakerRegistry registry) {
        succeed(registry, Duration.ofMillis(10));
    }

    private static void succeed(ModelBreakerRegistry registry, Duration duration) {
        permission(registry.tryAcquire(CHAT_TARGET, Capability.CHAT)).succeed(duration);
    }

    private static void open(ModelBreakerRegistry registry) {
        for (int call = 0; call < 4; call++) {
            fail(registry, AttemptFailureType.SERVER_ERROR_RESPONSE);
        }
        assertThat(registry.snapshot(CHAT_TARGET, Capability.CHAT).state())
                .isEqualTo(BreakerState.OPEN);
    }

    private static BreakerPermission permission(BreakerAdmission admission) {
        assertThat(admission).isInstanceOf(BreakerPermission.class);
        return (BreakerPermission) admission;
    }

    private static AiRuntimePolicy.BreakerLimits limits() {
        return new AiRuntimePolicy.BreakerLimits(
                4,
                4,
                50,
                50,
                Duration.ofSeconds(30),
                2,
                Duration.ofSeconds(30));
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial) {
            this.instant = new AtomicReference<>(initial);
        }

        void advance(Duration duration) {
            instant.updateAndGet(current -> current.plus(duration));
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return zone.equals(ZoneOffset.UTC) ? this : Clock.fixed(instant(), zone);
        }

        @Override
        public Instant instant() {
            return instant.get();
        }
    }
}
