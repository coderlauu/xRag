package com.app.ai.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.app.ai.diagnostic.AttemptDiagnostic;
import com.app.ai.diagnostic.AttemptKind;
import com.app.ai.diagnostic.AttemptOutcome;
import com.app.ai.diagnostic.BreakerState;
import com.app.ai.diagnostic.CallOutcome;
import com.app.ai.diagnostic.CompletedCallDiagnostic;
import com.app.ai.diagnostic.JdbcDiagnosticStore;
import com.app.ai.diagnostic.RouteCandidateDiagnostic;
import com.app.ai.diagnostic.RouteDecision;
import com.app.ai.diagnostic.RouteEventDiagnostic;
import com.app.ai.diagnostic.RouteEventType;
import com.app.ai.diagnostic.RouteRejectionReason;
import com.app.ai.diagnostic.TimingDiagnostic;
import com.app.ai.diagnostic.UsageDiagnostic;
import com.app.ai.model.Capability;
import com.app.ai.model.ChatFinishReason;
import com.app.ai.model.DeploymentId;
import com.app.ai.model.ExecutionTarget;
import com.app.ai.model.FailurePhase;
import com.app.ai.model.ModelErrorType;
import com.app.ai.model.ModelIdentity;
import com.app.ai.model.ProviderId;
import com.app.ai.model.QuotaDomain;
import com.app.ai.model.RerankDegradationReason;
import com.app.ai.model.ServiceTier;
import com.app.ai.model.TerminationReason;
import com.app.web.ApiException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** API-02~API-11 与 DIA-21。 */
class DiagnosticCallQueryIntegrationTests {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-08-07T12:00:00Z");
    private static final QuotaDomain QUOTA = new QuotaDomain("quota-a");
    private static final ExecutionTarget CHAT_A = target("provider-a", "chat-a");
    private static final ExecutionTarget CHAT_B = target("provider-b", "chat-b");

    private final String baseUrl = withoutQuery(System.getProperty(
            "spring.datasource.url", "jdbc:postgresql://127.0.0.1:5432/app"));
    private final String username = System.getProperty("spring.datasource.username", "app");
    private final String password = System.getProperty("spring.datasource.password", "app");

    private String schema;
    private DriverManagerDataSource dataSource;
    private JdbcTemplate jdbc;
    private JdbcDiagnosticStore store;
    private DiagnosticCallQueryService service;

    @BeforeEach
    void createSchema() throws SQLException {
        schema = "xrag_diag_calls_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("create schema " + schema);
        }
        dataSource = new DriverManagerDataSource(schemaUrl(), username, password);
        jdbc = new JdbcTemplate(dataSource);
        Flyway.configure()
                .dataSource(dataSource)
                .defaultSchema(schema)
                .schemas(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
        store = new JdbcDiagnosticStore(jdbc, new DataSourceTransactionManager(dataSource));
        service = new DiagnosticCallQueryService(jdbc, Clock.fixed(NOW.toInstant(), ZoneOffset.UTC));
    }

    @AfterEach
    void dropSchema() throws SQLException {
        try (Connection connection = DriverManager.getConnection(baseUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + schema + " cascade");
        }
    }

    @Test
    void api02_默认查询只覆盖最近24小时并按开始时间倒序() {
        store.write(chatCall(id(1), NOW.minusHours(1), CallOutcome.SUCCEEDED, ServiceTier.BALANCED));
        store.write(chatCall(id(2), NOW.minusHours(3), CallOutcome.SUCCEEDED, ServiceTier.BALANCED));
        store.write(chatCall(id(3), NOW.minusHours(30), CallOutcome.SUCCEEDED, ServiceTier.BALANCED));

        var page = service.list(query().build());

        assertThat(page.page()).isEqualTo(1);
        assertThat(page.size()).isEqualTo(20);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(AiDiagnosticCall::id).containsExactly(id(1), id(2));
    }

    @Test
    void api03_多字段筛选取and且total正确() {
        store.write(chatCall(id(1), NOW.minusHours(1), CallOutcome.SUCCEEDED, ServiceTier.BALANCED));
        store.write(chatCall(id(2), NOW.minusHours(1), CallOutcome.FAILED, ServiceTier.BALANCED));
        store.write(chatCall(id(3), NOW.minusHours(1), CallOutcome.SUCCEEDED, ServiceTier.QUALITY));

        var page = service.list(query()
                .capability("CHAT").outcomes(List.of("SUCCEEDED")).tier("BALANCED")
                .providerId("provider-a").build());

        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items()).extracting(AiDiagnosticCall::id).containsExactly(id(1));
    }

    @Test
    void api04_requestId精确匹配内部id或脱敏providerId() {
        store.write(chatCall(id(1), NOW.minusHours(1), CallOutcome.SUCCEEDED, ServiceTier.BALANCED));
        store.write(chatCall(id(2), NOW.minusHours(1), CallOutcome.SUCCEEDED, ServiceTier.BALANCED));

        assertThat(service.list(query().requestId("request-" + id(1)).build()).items())
                .extracting(AiDiagnosticCall::id).containsExactly(id(1));
        assertThat(service.list(query().requestId("req-***-" + id(2)).build()).items())
                .extracting(AiDiagnosticCall::id).containsExactly(id(2));
        // 不做模糊：前缀查不到任何东西。
        assertThat(service.list(query().requestId("request-").build()).items()).isEmpty();
    }

    @Test
    void api05_时间跨度超过7天返回invalidFilter() {
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> service.list(query().from(NOW.minusDays(8)).to(NOW).build()))
                .satisfies(failure -> {
                    assertThat(failure.code()).isEqualTo("INVALID_FILTER");
                    assertThat(failure.status().value()).isEqualTo(400);
                });
    }

    @Test
    void api06_size超过100被截到100() {
        store.write(chatCall(id(1), NOW.minusHours(1), CallOutcome.SUCCEEDED, ServiceTier.BALANCED));

        assertThat(service.list(query().size(500).build()).size()).isEqualTo(100);
    }

    @Test
    void api07_不存在或已清理的调用返回稳定404() {
        assertThatExceptionOfType(ApiException.class)
                .isThrownBy(() -> service.detail(id(9)))
                .satisfies(failure -> {
                    assertThat(failure.status().value()).isEqualTo(404);
                    assertThat(failure.code()).isEqualTo("NOT_FOUND");
                });
    }

    @Test
    void api08_详情按真实顺序返回候选与attempt并给出能力详情() {
        store.write(chatCall(id(1), NOW.minusHours(1), CallOutcome.SUCCEEDED, ServiceTier.BALANCED));

        var detail = service.detail(id(1));

        assertThat(detail.call().capability()).isEqualTo("CHAT");
        assertThat(detail.candidates()).extracting(AiDiagnosticCallDetail.Candidate::candidateOrdinal)
                .containsExactly(0, 1);
        assertThat(detail.candidates().get(1).decision()).isEqualTo("FILTERED");
        assertThat(detail.candidates().get(1).rejectionReason())
                .isEqualTo(RouteRejectionReason.SERVICE_TIER_MISMATCH.name());
        assertThat(detail.routeEvents()).extracting(AiDiagnosticCallDetail.RouteEvent::sequenceNo)
                .isSorted();
        assertThat(detail.attempts()).extracting(AiDiagnosticCallDetail.Attempt::attemptNo)
                .containsExactly(1);
        assertThat(detail.capabilityDetails().chat().finishReason()).isEqualTo("STOP");
        assertThat(detail.capabilityDetails().stream()).isNull();
        // 非 Embedding 能力的 attempt 输入范围恒为 null。
        assertThat(detail.attempts().get(0).inputFromInclusive()).isNull();
    }

    @Test
    void api09_failoverOccurred严格匹配切换次数() {
        store.write(chatCall(id(1), NOW.minusHours(1), CallOutcome.SUCCEEDED, ServiceTier.BALANCED));
        store.write(failoverCall(id(2), NOW.minusHours(1)));

        assertThat(service.list(query().failoverOccurred(true).build()).items())
                .extracting(AiDiagnosticCall::id).containsExactly(id(2));
        assertThat(service.list(query().failoverOccurred(false).build()).items())
                .extracting(AiDiagnosticCall::id).containsExactly(id(1));
    }

    @Test
    void api10_同一outcome字段内取or其余字段仍取and() {
        store.write(chatCall(id(1), NOW.minusHours(1), CallOutcome.FAILED, ServiceTier.BALANCED));
        store.write(streamFailedCall(id(2), NOW.minusHours(1)));
        store.write(chatCall(id(3), NOW.minusHours(1), CallOutcome.SUCCEEDED, ServiceTier.BALANCED));

        var page = service.list(query()
                .outcomes(List.of("FAILED", "STREAM_FAILED")).build());

        assertThat(page.total()).isEqualTo(2);
        assertThat(page.items()).extracting(AiDiagnosticCall::id).contains(id(1), id(2));
        // 与其他字段仍取 AND。
        assertThat(service.list(query()
                .outcomes(List.of("FAILED", "STREAM_FAILED")).capability("STREAM_CHAT").build())
                .items()).extracting(AiDiagnosticCall::id).containsExactly(id(2));
    }

    @Test
    void api11_provider筛选命中失败的首选而不是只看调用级实际路由() {
        store.write(failoverCall(id(1), NOW.minusHours(1)));

        // provider-a 是失败的首选，调用级 actual_provider_id 是 provider-b。
        assertThat(jdbc.queryForObject(
                "select actual_provider_id from ai_logical_call where id = ?", String.class, id(1)))
                .isEqualTo("provider-b");
        assertThat(service.list(query().providerId("provider-a").build()).items())
                .extracting(AiDiagnosticCall::id).containsExactly(id(1));
        assertThat(service.list(query().providerId("provider-b").build()).items())
                .extracting(AiDiagnosticCall::id).containsExactly(id(1));
    }

    @Test
    void api11_provider筛选命中mixedEmbedding的贡献者() {
        store.write(mixedEmbeddingCall(id(1), NOW.minusHours(1)));

        // mixed 调用的 actual 三字段为 null，只能靠 attempt 命中。
        assertThat(service.list(query().providerId("provider-b").build()).items())
                .extracting(AiDiagnosticCall::id).containsExactly(id(1));

        var detail = service.detail(id(1));
        var embedding = detail.capabilityDetails().embedding();
        assertThat(embedding.mixedExecution()).isTrue();
        assertThat(embedding.contributingDeployments()).hasSize(2);
        assertThat(embedding.batches()).extracting(
                        AiDiagnosticCallDetail.CapabilityDetails.Embedding.Batch::workUnitIndex)
                .containsExactly(0, 1);
        assertThat(embedding.batches().get(0).inputFromInclusive()).isZero();
        assertThat(embedding.batches().get(1).inputToExclusive()).isEqualTo(96);
        assertThat(detail.call().actualProviderId()).isNull();
    }

    @Test
    void lastAttemptTarget只是最后一次尝试不冒充成功路由() {
        store.write(allAttemptsFailedCall(id(1), NOW.minusHours(1)));

        var item = service.list(query().build()).items().get(0);
        assertThat(item.actualProviderId()).isNull();
        assertThat(item.lastAttemptTarget().providerId()).isEqualTo("provider-b");

        var detail = service.detail(id(1));
        assertThat(detail.call().actualProviderId()).isNull();
        assertThat(detail.call().lastAttemptTarget().deploymentId()).isEqualTo("chat-b");
    }

    @Test
    void dia21_稳定的错误类型与失败阶段在call和attempt上原样往返() {
        store.write(allAttemptsFailedCall(id(1), NOW.minusHours(1)));

        var detail = service.detail(id(1));

        assertThat(detail.call().normalizedErrorType()).isEqualTo(ModelErrorType.SERVER_ERROR.name());
        assertThat(detail.call().failurePhase()).isEqualTo(FailurePhase.RESPONSE_HEADERS.name());
        assertThat(detail.call().terminationReason())
                .isEqualTo(TerminationReason.ALL_CANDIDATES_EXHAUSTED.name());
        assertThat(detail.attempts()).allSatisfy(attempt -> {
            assertThat(attempt.normalizedErrorType()).isEqualTo(ModelErrorType.SERVER_ERROR.name());
            assertThat(attempt.failurePhase()).isEqualTo(FailurePhase.RESPONSE_HEADERS.name());
        });
    }

    @Test
    void rerank的noop详情返回降级事实而不是伪造的排序() {
        store.write(rerankNoopCall(id(1), NOW.minusHours(1)));

        var rerank = service.detail(id(1)).capabilityDetails().rerank();

        assertThat(rerank.rerankApplied()).isFalse();
        assertThat(rerank.degradationReason())
                .isEqualTo(RerankDegradationReason.CAPABILITY_NOT_DECLARED.name());
        assertThat(rerank.documentCount()).isEqualTo(3);
        assertThat(rerank.topN()).isEqualTo(2);
        assertThat(service.detail(id(1)).attempts()).isEmpty();
    }

    // ---------- fixtures ----------

    private static CompletedCallDiagnostic chatCall(
            UUID id, OffsetDateTime startedAt, CallOutcome outcome, ServiceTier tier) {
        boolean succeeded = outcome == CallOutcome.SUCCEEDED;
        return new CompletedCallDiagnostic(
                id, "request-" + id, Capability.CHAT, tier, null, false,
                "test-v1", "a".repeat(64),
                outcome,
                succeeded ? null : ModelErrorType.SERVER_ERROR,
                succeeded ? null : FailurePhase.RESPONSE_HEADERS,
                succeeded ? TerminationReason.COMPLETED : TerminationReason.ALL_CANDIDATES_EXHAUSTED,
                succeeded ? 200 : 500,
                succeeded ? ChatFinishReason.STOP : null,
                false, null, null,
                new UsageDiagnostic(12, 34, null, null, null, null, null, null),
                timing(startedAt),
                List.of(
                        candidate(0, CHAT_A, Capability.CHAT, tier, RouteDecision.ELIGIBLE, null, 0),
                        candidate(1, CHAT_B, Capability.CHAT, tier, RouteDecision.FILTERED,
                                RouteRejectionReason.SERVICE_TIER_MISMATCH, null)),
                List.of(routeCompleted(1)),
                List.of(attempt(1, AttemptKind.INITIAL, CHAT_A, 0,
                        succeeded ? AttemptOutcome.SUCCEEDED : AttemptOutcome.FAILED,
                        "req-***-" + id)),
                List.of());
    }

    /** 首选 provider-a 失败后切到 provider-b 成功。 */
    private static CompletedCallDiagnostic failoverCall(UUID id, OffsetDateTime startedAt) {
        return new CompletedCallDiagnostic(
                id, "request-" + id, Capability.CHAT, ServiceTier.BALANCED, null, false,
                "test-v1", "a".repeat(64),
                CallOutcome.SUCCEEDED, null, null, TerminationReason.COMPLETED,
                200, ChatFinishReason.STOP, false, null, null,
                new UsageDiagnostic(1, 2, null, null, null, null, null, null),
                timing(startedAt),
                List.of(
                        candidate(0, CHAT_A, Capability.CHAT, ServiceTier.BALANCED,
                                RouteDecision.ELIGIBLE, null, 0),
                        candidate(1, CHAT_B, Capability.CHAT, ServiceTier.BALANCED,
                                RouteDecision.ELIGIBLE, null, 1)),
                List.of(routeCompleted(1)),
                List.of(
                        attempt(1, AttemptKind.INITIAL, CHAT_A, 0, AttemptOutcome.FAILED, null),
                        attempt(2, AttemptKind.FAILOVER, CHAT_B, 1, AttemptOutcome.SUCCEEDED,
                                "req-***-ok")),
                List.of());
    }

    /** 两个候选都失败：没有贡献者，但有 attempt。 */
    private static CompletedCallDiagnostic allAttemptsFailedCall(UUID id, OffsetDateTime startedAt) {
        return new CompletedCallDiagnostic(
                id, "request-" + id, Capability.CHAT, ServiceTier.BALANCED, null, false,
                "test-v1", "a".repeat(64),
                CallOutcome.FAILED, ModelErrorType.SERVER_ERROR, FailurePhase.RESPONSE_HEADERS,
                TerminationReason.ALL_CANDIDATES_EXHAUSTED, 500, null, false, null, null,
                new UsageDiagnostic(null, null, null, null, null, null, null, null),
                timing(startedAt),
                List.of(candidate(0, CHAT_A, Capability.CHAT, ServiceTier.BALANCED,
                        RouteDecision.ELIGIBLE, null, 0)),
                List.of(routeCompleted(1)),
                List.of(
                        attempt(1, AttemptKind.INITIAL, CHAT_A, 0, AttemptOutcome.FAILED, null),
                        attempt(2, AttemptKind.FAILOVER, CHAT_B, 0, AttemptOutcome.FAILED, null)),
                List.of());
    }

    private static CompletedCallDiagnostic streamFailedCall(UUID id, OffsetDateTime startedAt) {
        ExecutionTarget target = target("provider-a", "stream-a");
        return new CompletedCallDiagnostic(
                id, "request-" + id, Capability.STREAM_CHAT, ServiceTier.BALANCED, null, true,
                "test-v1", "a".repeat(64),
                CallOutcome.STREAM_FAILED, ModelErrorType.STREAM_INTERRUPTED,
                FailurePhase.UPSTREAM_STREAM,
                TerminationReason.STREAM_INTERRUPTED_AFTER_COMMIT, 200, null, true, null, null,
                new UsageDiagnostic(1, 2, null, null, null, null, null, 18),
                timing(startedAt),
                List.of(candidate(0, target, Capability.STREAM_CHAT, ServiceTier.BALANCED,
                        RouteDecision.ELIGIBLE, null, 0)),
                List.of(routeCompleted(1)),
                List.of(attempt(1, AttemptKind.INITIAL, target, 0,
                        AttemptOutcome.STREAM_FAILED, "req-***-s")),
                List.of());
    }

    private static CompletedCallDiagnostic mixedEmbeddingCall(UUID id, OffsetDateTime startedAt) {
        ExecutionTarget first = target("provider-a", "embed-a");
        ExecutionTarget second = target("provider-b", "embed-b");
        return new CompletedCallDiagnostic(
                id, "request-" + id, Capability.EMBEDDING, null,
                new ModelIdentity("kb-embedding-v1"), false,
                "test-v1", "a".repeat(64),
                CallOutcome.SUCCEEDED, null, null, TerminationReason.COMPLETED,
                200, null, false, null, null,
                new UsageDiagnostic(null, null, 96, 2, 1024, null, null, null),
                timing(startedAt),
                List.of(
                        candidate(0, first, Capability.EMBEDDING, null, RouteDecision.ELIGIBLE, null, 0),
                        candidate(1, second, Capability.EMBEDDING, null, RouteDecision.ELIGIBLE, null, 1)),
                List.of(routeCompleted(2)),
                List.of(
                        embeddingAttempt(1, 0, 0, 64, first, AttemptKind.INITIAL),
                        embeddingAttempt(2, 1, 64, 96, second, AttemptKind.FAILOVER)),
                List.of());
    }

    private static CompletedCallDiagnostic rerankNoopCall(UUID id, OffsetDateTime startedAt) {
        return new CompletedCallDiagnostic(
                id, "request-" + id, Capability.RERANK, ServiceTier.BALANCED, null, false,
                "test-v1", "a".repeat(64),
                CallOutcome.DEGRADED, null, null, TerminationReason.NOOP_DEGRADED,
                null, null, false, false, RerankDegradationReason.CAPABILITY_NOT_DECLARED,
                new UsageDiagnostic(null, null, null, null, null, 3, 2, null),
                timing(startedAt),
                List.of(),
                List.of(routeCompleted(0)),
                List.of(),
                List.of());
    }

    private static AttemptDiagnostic attempt(
            int attemptNo,
            AttemptKind kind,
            ExecutionTarget target,
            int candidateOrder,
            AttemptOutcome outcome,
            String maskedRequestId) {
        boolean failed = outcome != AttemptOutcome.SUCCEEDED;
        return new AttemptDiagnostic(
                attemptNo, 0, null, null, candidateOrder, kind,
                attemptNo * 2, attemptNo * 2 + 1,
                target, QUOTA, BreakerState.CLOSED, BreakerState.CLOSED,
                outcome,
                failed
                        ? (outcome == AttemptOutcome.STREAM_FAILED
                                ? ModelErrorType.STREAM_INTERRUPTED
                                : ModelErrorType.SERVER_ERROR)
                        : null,
                failed
                        ? (outcome == AttemptOutcome.STREAM_FAILED
                                ? FailurePhase.UPSTREAM_STREAM
                                : FailurePhase.RESPONSE_HEADERS)
                        : null,
                failed ? 500 : 200,
                maskedRequestId,
                0L, null, null, null, 5L, 1, 2, null);
    }

    private static AttemptDiagnostic embeddingAttempt(
            int attemptNo, int workUnitIndex, int from, int to, ExecutionTarget target,
            AttemptKind kind) {
        return new AttemptDiagnostic(
                attemptNo, workUnitIndex, from, to, workUnitIndex, kind,
                attemptNo * 2, attemptNo * 2 + 1,
                target, QUOTA, BreakerState.CLOSED, BreakerState.CLOSED,
                AttemptOutcome.SUCCEEDED, null, null, 200, null,
                0L, null, null, null, 5L, null, null, null);
    }

    private static RouteCandidateDiagnostic candidate(
            int ordinal,
            ExecutionTarget target,
            Capability capability,
            ServiceTier tier,
            RouteDecision decision,
            RouteRejectionReason rejection,
            Integer plannedOrder) {
        return new RouteCandidateDiagnostic(
                ordinal, target, capability, tier, QUOTA, decision, rejection, plannedOrder,
                ordinal == 0, 10, BreakerState.CLOSED);
    }

    private static RouteEventDiagnostic routeCompleted(int eligibleCandidateCount) {
        return new RouteEventDiagnostic(
                1, 0, RouteEventType.ROUTE_COMPLETED, null, null, null,
                null, null, null, null, null, eligibleCandidateCount);
    }

    private static TimingDiagnostic timing(OffsetDateTime startedAt) {
        return new TimingDiagnostic(1L, 842L, 1204L, 1216L, 3842, startedAt, startedAt.plusSeconds(4));
    }

    private static ExecutionTarget target(String providerId, String deploymentId) {
        return new ExecutionTarget(
                new ProviderId(providerId),
                new DeploymentId(deploymentId),
                new ModelIdentity(deploymentId + "-v1"));
    }

    private static UUID id(int ordinal) {
        return UUID.fromString("00000000-0000-0000-0000-0000000009%02d".formatted(ordinal));
    }

    private static QueryBuilder query() {
        return new QueryBuilder();
    }

    private String schemaUrl() {
        return baseUrl + "?currentSchema=" + schema + ",public";
    }

    private static String withoutQuery(String url) {
        int query = url.indexOf('?');
        return query < 0 ? url : url.substring(0, query);
    }

    private static final class QueryBuilder {

        private String capability;
        private List<String> outcomes = List.of();
        private String tier;
        private String providerId;
        private Boolean failoverOccurred;
        private String requestId;
        private OffsetDateTime from;
        private OffsetDateTime to;
        private Integer page;
        private Integer size;

        QueryBuilder capability(String value) {
            this.capability = value;
            return this;
        }

        QueryBuilder outcomes(List<String> value) {
            this.outcomes = value;
            return this;
        }

        QueryBuilder tier(String value) {
            this.tier = value;
            return this;
        }

        QueryBuilder providerId(String value) {
            this.providerId = value;
            return this;
        }

        QueryBuilder failoverOccurred(Boolean value) {
            this.failoverOccurred = value;
            return this;
        }

        QueryBuilder requestId(String value) {
            this.requestId = value;
            return this;
        }

        QueryBuilder from(OffsetDateTime value) {
            this.from = value;
            return this;
        }

        QueryBuilder to(OffsetDateTime value) {
            this.to = value;
            return this;
        }

        QueryBuilder size(Integer value) {
            this.size = value;
            return this;
        }

        DiagnosticCallQuery build() {
            return new DiagnosticCallQuery(
                    capability, outcomes, tier, providerId, failoverOccurred, requestId,
                    from, to, page, size);
        }
    }
}
