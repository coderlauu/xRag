package com.app.ai.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.app.ai.breaker.BreakerPermission;
import com.app.ai.breaker.BreakerTransitionReason;
import com.app.ai.breaker.Resilience4jModelBreakerRegistry;
import com.app.ai.diagnostic.BreakerState;
import com.app.ai.diagnostic.CompletedCallDiagnostic;
import com.app.ai.diagnostic.DiagnosticRecorder;
import com.app.ai.model.AiRuntimePolicy;
import com.app.ai.model.Capability;
import com.app.ai.model.DataRegion;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.ModelCatalogSnapshot;
import com.app.ai.model.ModelDeployment;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ModelProvider;
import com.app.ai.model.ProviderConfigurationStatus;
import com.app.ai.model.ProviderId;
import com.app.ai.model.ServiceTier;
import com.app.ai.provider.AttemptFailureType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DiagnosticsOverviewServiceTests {

    private static final ProviderId PROVIDER_A = new ProviderId("provider-a");
    private static final ProviderId PROVIDER_B = new ProviderId("provider-b");
    private static final DeploymentId CHAT_A = new DeploymentId("chat-a");
    private static final DeploymentId CHAT_B = new DeploymentId("chat-b");
    private static final String SECRET_VALUE = "sk-live-TOP_SECRET_VALUE";

    @Test
    void api01_没有任何Provider时返回稳定空对象而不是失败() {
        var catalog = new ModelCatalogSnapshot(
                "test-v1", "a".repeat(64), Map.of(), Map.of(), runtimePolicy());

        AiDiagnosticsOverview overview = service(catalog, breakers(catalog), null).overview();

        assertThat(overview.deployments()).isEmpty();
        assertThat(overview.breakers()).isEmpty();
        assertThat(overview.policyVersion()).isEqualTo("test-v1");
        assertThat(overview.summary().eligibleCandidates()).isZero();
        assertThat(overview.summary().openBreakers()).isZero();
        assertThat(overview.retention().maxAgeDays()).isEqualTo(7);
        assertThat(overview.retention().maxLogicalCalls()).isEqualTo(10_000);
    }

    @Test
    void 未配置与禁用的deployment不计入合格候选且不伪装成breakerOpen() {
        var catalog = catalog();

        AiDiagnosticsOverview overview = service(catalog, breakers(catalog), null).overview();

        // chat-a：enabled + CONFIGURED，两个能力各算一个单元；chat-b：UNCONFIGURED 不计。
        assertThat(overview.summary().eligibleCandidates()).isEqualTo(2);
        assertThat(overview.deployments())
                .extracting(deployment -> deployment.deploymentId(),
                        deployment -> deployment.enabled(),
                        deployment -> deployment.configurationStatus())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("chat-a", true, "CONFIGURED"),
                        org.assertj.core.groups.Tuple.tuple("chat-b", false, "UNCONFIGURED"));
        assertThat(overview.breakers()).isEmpty();
        assertThat(overview.summary().openBreakers()).isZero();
    }

    @Test
    void breakerOpen状态与最近计数按注册表如实呈现() {
        var catalog = catalog();
        var registry = breakers(catalog);
        ExecutionTarget target = new ExecutionTarget(
                PROVIDER_A, CHAT_A, new ModelIdentity("chat-a-v1"));
        for (int call = 0; call < catalog.runtimePolicy().breaker().minimumNumberOfCalls(); call++) {
            ((BreakerPermission) registry.tryAcquire(target, Capability.CHAT))
                    .fail(Duration.ofMillis(10), AttemptFailureType.SERVER_ERROR_RESPONSE);
        }

        AiDiagnosticsOverview overview = service(catalog, registry, null).overview();

        assertThat(overview.summary().openBreakers()).isEqualTo(1);
        assertThat(overview.breakers()).singleElement().satisfies(breaker -> {
            assertThat(breaker.breakerKey()).isEqualTo("provider-a/chat-a/CHAT");
            assertThat(breaker.state()).isEqualTo(BreakerState.OPEN.name());
            assertThat(breaker.allowsCall()).isFalse();
            assertThat(breaker.capability()).isEqualTo(Capability.CHAT);
            assertThat(breaker.recent().failure()).isEqualTo(5);
        });
        // OPEN 的 CHAT 单元不再算合格候选，STREAM_CHAT 仍然算。
        assertThat(overview.summary().eligibleCandidates()).isEqualTo(1);
    }

    @Test
    void halfOpen不计入openBreakers且许可占满时不再算合格候选() {
        var catalog = catalog();
        var breaker = catalog.runtimePolicy().breaker();
        var clock = new MutableClock(Instant.parse("2026-08-03T00:00:00Z"));
        var registry = new Resilience4jModelBreakerRegistry(breaker, clock, transition -> true);
        ExecutionTarget target = new ExecutionTarget(
                PROVIDER_A, CHAT_A, new ModelIdentity("chat-a-v1"));
        for (int call = 0; call < breaker.minimumNumberOfCalls(); call++) {
            ((BreakerPermission) registry.tryAcquire(target, Capability.CHAT))
                    .fail(Duration.ofMillis(10), AttemptFailureType.SERVER_ERROR_RESPONSE);
        }
        clock.advance(breaker.openWaitDuration());
        // 两个 halfOpen 许可都拿走且不结算，registry 从此不再放行。
        for (int probe = 0; probe < breaker.halfOpenPermits(); probe++) {
            registry.tryAcquire(target, Capability.CHAT);
        }

        AiDiagnosticsOverview overview = service(catalog, registry, null).overview();

        // HALF_OPEN 是"正在试探"，不是开路——不能混进 openBreakers 让页面误报故障数。
        assertThat(overview.summary().openBreakers()).isZero();
        assertThat(overview.breakers()).singleElement().satisfies(halfOpen -> {
            assertThat(halfOpen.state()).isEqualTo(BreakerState.HALF_OPEN.name());
            assertThat(halfOpen.halfOpenPermissionInUse()).isTrue();
            assertThat(halfOpen.allowsCall()).isFalse();
            assertThat(halfOpen.lastChangeReason())
                    .isEqualTo(BreakerTransitionReason.WAIT_ELAPSED.name());
        });
        // 合格候选跟着 allowsCall 走，与状态名无关：许可占满的 CHAT 不算，STREAM_CHAT 仍然算。
        assertThat(overview.summary().eligibleCandidates()).isEqualTo(1);
    }

    @Test
    void 计数读取失败时返回空而不是伪造为零() {
        var catalog = catalog();
        DiagnosticRecorder failing = new DiagnosticRecorder() {
            @Override
            public boolean offer(CompletedCallDiagnostic diagnostic) {
                return true;
            }

            @Override
            public long droppedCount() {
                throw new IllegalStateException("计数不可用");
            }

            @Override
            public long writeFailureCount() {
                throw new IllegalStateException("计数不可用");
            }
        };

        AiDiagnosticsOverview overview = new DiagnosticsOverviewService(
                catalog, breakers(catalog), failing, null, Clock.systemUTC(), 7, 10_000).overview();

        assertThat(overview.summary().diagnosticsDroppedSinceStart()).isNull();
        assertThat(overview.summary().diagnosticWriteFailuresSinceStart()).isNull();
        assertThat(overview.summary().failovers24h()).isNull();
        assertThat(overview.summary().failedOrInterrupted24h()).isNull();
        assertThat(overview.retention().oldestAvailableAt()).isNull();
    }

    @Test
    void cfg10_序列化结果不含密钥值与秘密属性名() throws Exception {
        var catalog = catalog();

        String json = new ObjectMapper().writeValueAsString(
                service(catalog, breakers(catalog), null).overview());

        assertThat(json).doesNotContain(SECRET_VALUE);
        assertThat(json).doesNotContain("PROVIDER_A_API_KEY");
        assertThat(json).doesNotContainIgnoringCase("authorization");
        assertThat(json).doesNotContainIgnoringCase("apikey");
        assertThat(json).doesNotContainIgnoringCase("secret");
    }

    /**
     * 纪律：没有成功记录可读时 {@code verifiedCapabilities} 必须为空，绝不退化成 catalog 的
     * {@code capabilities}。这里 {@code jdbc = null}（诊断库读不到），而 chat-a 在配置里声明了
     * CHAT + STREAM_CHAT——一旦有人图省事让它 fallback 到理论能力，这条就会红。
     * "有成功记录时按能力填充"必须对着真库验，在 {@code DiagnosticsOverviewIntegrationTests}。
     */
    @Test
    void verifiedCapabilities没有成功记录时为空且不拿理论能力顶替() {
        var catalog = catalog();

        AiDiagnosticsOverview overview = service(catalog, breakers(catalog), null).overview();

        assertThat(overview.deployments()).first().satisfies(deployment -> {
            assertThat(deployment.capabilities())
                    .containsExactly(Capability.CHAT, Capability.STREAM_CHAT);
            assertThat(deployment.verifiedCapabilities()).isEmpty();
        });
    }

    private static DiagnosticsOverviewService service(
            ModelCatalogSnapshot catalog,
            Resilience4jModelBreakerRegistry registry,
            JdbcTemplate jdbc) {
        return new DiagnosticsOverviewService(
                catalog, registry, new CountingRecorder(), jdbc, Clock.systemUTC(), 7, 10_000);
    }

    private static Resilience4jModelBreakerRegistry breakers(ModelCatalogSnapshot catalog) {
        return new Resilience4jModelBreakerRegistry(
                catalog.runtimePolicy().breaker(), Clock.systemUTC(), transition -> true);
    }

    private static ModelCatalogSnapshot catalog() {
        var providers = new LinkedHashMap<ProviderId, ModelProvider>();
        providers.put(PROVIDER_A, new ModelProvider(
                PROVIDER_A, "openai-compatible", "供应商甲",
                URI.create("http://127.0.0.1:1/v1"), "PROVIDER_A_API_KEY",
                new DataRegion("CN_TEST"), new com.app.ai.model.QuotaDomain("quota-a"),
                ProviderConfigurationStatus.CONFIGURED));
        providers.put(PROVIDER_B, new ModelProvider(
                PROVIDER_B, "openai-compatible", "供应商乙",
                URI.create("http://127.0.0.1:2/v1"), "PROVIDER_B_API_KEY",
                new DataRegion("CN_TEST"), new com.app.ai.model.QuotaDomain("quota-b"),
                ProviderConfigurationStatus.UNCONFIGURED));
        var deployments = new LinkedHashMap<DeploymentId, ModelDeployment>();
        deployments.put(CHAT_A, new ModelDeployment(
                CHAT_A, PROVIDER_A, "chat-a-model", new ModelIdentity("chat-a-v1"),
                Set.of(Capability.CHAT, Capability.STREAM_CHAT), Set.of(ServiceTier.BALANCED),
                true, 10, true, null));
        deployments.put(CHAT_B, new ModelDeployment(
                CHAT_B, PROVIDER_B, "chat-b-model", new ModelIdentity("chat-b-v1"),
                Set.of(Capability.CHAT), Set.of(ServiceTier.BALANCED),
                false, 20, false, null));
        return new ModelCatalogSnapshot(
                "test-v1", "a".repeat(64), providers, deployments, runtimePolicy());
    }

    private static AiRuntimePolicy runtimePolicy() {
        return new AiRuntimePolicy(
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(45), 3, 2),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(120), 3, 1),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofMinutes(15), 3, 2),
                new AiRuntimePolicy.CapabilityLimits(Duration.ofSeconds(20), 3, 2),
                new AiRuntimePolicy.StreamLimits(32, 65_536, 65_536),
                new AiRuntimePolicy.BreakerLimits(
                        20, 5, 50, 50, Duration.ofSeconds(30), 2, Duration.ofSeconds(30)));
    }

    /** 推进时间才能把 OPEN 等到 HALF_OPEN；与 ModelBreakerRegistryTests 里的同名工具一致。 */
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

    private static final class CountingRecorder implements DiagnosticRecorder {

        @Override
        public boolean offer(CompletedCallDiagnostic diagnostic) {
            return true;
        }

        @Override
        public long droppedCount() {
            return 0;
        }

        @Override
        public long writeFailureCount() {
            return 0;
        }
    }
}
