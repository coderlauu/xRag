package com.app.ai.web;

import com.app.web.ApiException;
import com.app.web.PageResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

/**
 * api.md §6~§7 的只读查询。
 *
 * <p>筛选口径有两条容易踩错的：`providerId` 与脱敏 Provider request ID 都走
 * {@code ai_attempt exists} 精确匹配，而不是比对调用级的 `actual_*` 单值——否则失败的首选
 * Provider 和 mixed Embedding 的贡献者都会从筛选结果里消失。`requestId` 一律精确匹配，
 * 不做 {@code %like%}：大表模糊搜索既慢又等于开放标识枚举。
 */
@Service
public class DiagnosticCallQueryService {

    /** 时间窗口上限，与保留期一致：超过就没有数据可查，让它 400 比返回空列表更诚实。 */
    static final Duration MAX_WINDOW = Duration.ofDays(7);

    private static final Duration DEFAULT_WINDOW = Duration.ofHours(24);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Autowired
    DiagnosticCallQueryService(JdbcTemplate jdbc) {
        this(jdbc, Clock.systemUTC());
    }

    public DiagnosticCallQueryService(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.clock = Objects.requireNonNull(clock);
    }

    public PageResponse<AiDiagnosticCall> list(DiagnosticCallQuery query) {
        Window window = window(query.from(), query.to());
        int page = PageResponse.normalizePage(query.page());
        int size = PageResponse.normalizeSize(query.size());

        List<Object> args = new ArrayList<>();
        String where = where(query, window, args);

        Long total = jdbc.queryForObject(
                "select count(*) from ai_logical_call c " + where, Long.class, args.toArray());
        long totalCount = total == null ? 0 : total;
        if (totalCount == 0) {
            return new PageResponse<>(List.of(), page, size, 0);
        }

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((long) (page - 1) * size);
        List<Row> rows = jdbc.query("""
                select c.id, c.request_id, c.capability, c.service_tier, c.outcome,
                       c.actual_provider_id, c.actual_deployment_id, c.actual_model_identity,
                       c.attempt_count, c.retry_count, c.failover_count,
                       c.mixed_execution, c.contributing_deployment_count, c.committed,
                       c.total_duration_ms, c.started_at, c.normalized_error_type
                  from ai_logical_call c
                """ + where + """
                 order by c.started_at desc, c.id desc
                 limit ? offset ?
                """, ROW_MAPPER, pageArgs.toArray());

        Map<UUID, AiDiagnosticCall.Target> lastAttempts = lastAttemptTargets(
                rows.stream().map(Row::id).toList());
        List<AiDiagnosticCall> items = rows.stream()
                .map(row -> row.toSummary(lastAttempts.get(row.id())))
                .toList();
        return new PageResponse<>(items, page, size, totalCount);
    }

    public AiDiagnosticCallDetail detail(UUID logicalCallId) {
        Objects.requireNonNull(logicalCallId);
        List<Row> rows = jdbc.query("""
                select c.id, c.request_id, c.capability, c.service_tier, c.outcome,
                       c.actual_provider_id, c.actual_deployment_id, c.actual_model_identity,
                       c.attempt_count, c.retry_count, c.failover_count,
                       c.mixed_execution, c.contributing_deployment_count, c.committed,
                       c.total_duration_ms, c.started_at, c.normalized_error_type
                  from ai_logical_call c
                 where c.id = ?
                """, ROW_MAPPER, logicalCallId);
        if (rows.isEmpty()) {
            // 已被保留任务清理掉的调用和从未存在的 ID 返回同一个 404：它们对调用方没有区别，
            // 而区分开等于告诉外部"这个 ID 曾经存在过"。
            throw ApiException.notFound("调用记录不存在或已超过保留期被清理。");
        }
        // 用显式 RowMapper 而不是 queryForMap：后者对 timestamptz 返回 java.sql.Timestamp，
        // 强转 OffsetDateTime 会在运行时炸。
        Extra extra = jdbc.query("""
                select required_model_identity, policy_version, config_fingerprint,
                       provider_request_id_masked, failure_phase, termination_reason, http_status,
                       route_duration_ms, first_upstream_event_ms, first_meaningful_chunk_ms,
                       downstream_commit_ms, completed_at, finish_reason,
                       input_token_count, output_token_count, embedding_input_count,
                       embedding_batch_count, embedding_dimensions, stream_event_count,
                       rerank_document_count, rerank_top_n, rerank_applied, degraded_reason
                  from ai_logical_call where id = ?
                """, EXTRA_MAPPER, logicalCallId).get(0);

        Row row = rows.get(0);
        List<AiDiagnosticCallDetail.Candidate> candidates = candidates(logicalCallId);
        List<AiDiagnosticCallDetail.RouteEvent> routeEvents = routeEvents(logicalCallId);
        List<AiDiagnosticCallDetail.Attempt> attempts = attempts(logicalCallId);
        AiDiagnosticCall.Target lastAttempt = attempts.isEmpty()
                ? null
                : target(
                        attempts.get(attempts.size() - 1).providerId(),
                        attempts.get(attempts.size() - 1).deploymentId(),
                        attempts.get(attempts.size() - 1).modelIdentity());

        return new AiDiagnosticCallDetail(
                new AiDiagnosticCallDetail.Call(
                        row.id(),
                        row.requestId(),
                        row.capability(),
                        row.serviceTier(),
                        extra.requiredModelIdentity(),
                        extra.policyVersion(),
                        extra.configFingerprint(),
                        row.outcome(),
                        row.actualProviderId(),
                        row.actualDeploymentId(),
                        row.actualModelIdentity(),
                        lastAttempt,
                        extra.providerRequestIdMasked(),
                        row.attemptCount(),
                        row.retryCount(),
                        row.failoverCount(),
                        row.failoverCount() > 0,
                        row.mixedExecution(),
                        row.contributingDeploymentCount(),
                        row.committed(),
                        row.normalizedErrorType(),
                        extra.failurePhase(),
                        extra.terminationReason(),
                        extra.httpStatus(),
                        extra.routeDurationMs(),
                        extra.firstUpstreamEventMs(),
                        extra.firstMeaningfulChunkMs(),
                        extra.downstreamCommitMs(),
                        row.totalDurationMs(),
                        row.startedAt(),
                        extra.completedAt()),
                candidates,
                routeEvents,
                attempts,
                capabilityDetails(row, extra, attempts));
    }

    private AiDiagnosticCallDetail.CapabilityDetails capabilityDetails(
            Row row, Extra extra, List<AiDiagnosticCallDetail.Attempt> attempts) {
        return switch (row.capability()) {
            case "CHAT" -> new AiDiagnosticCallDetail.CapabilityDetails(
                    new AiDiagnosticCallDetail.CapabilityDetails.Chat(
                            extra.finishReason(),
                            extra.inputTokenCount(),
                            extra.outputTokenCount()),
                    null, null, null);
            case "STREAM_CHAT" -> new AiDiagnosticCallDetail.CapabilityDetails(
                    null,
                    new AiDiagnosticCallDetail.CapabilityDetails.Stream(
                            extra.firstUpstreamEventMs(),
                            extra.firstMeaningfulChunkMs(),
                            extra.downstreamCommitMs(),
                            extra.streamEventCount(),
                            "STREAM_FAILED".equals(row.outcome())),
                    null, null);
            case "EMBEDDING" -> new AiDiagnosticCallDetail.CapabilityDetails(
                    null, null, embeddingDetails(row, extra, attempts), null);
            case "RERANK" -> new AiDiagnosticCallDetail.CapabilityDetails(
                    null, null, null,
                    new AiDiagnosticCallDetail.CapabilityDetails.Rerank(
                            extra.rerankDocumentCount(),
                            extra.rerankTopN(),
                            extra.rerankApplied(),
                            extra.degradedReason()));
            default -> new AiDiagnosticCallDetail.CapabilityDetails(null, null, null, null);
        };
    }

    private AiDiagnosticCallDetail.CapabilityDetails.Embedding embeddingDetails(
            Row row, Extra extra, List<AiDiagnosticCallDetail.Attempt> attempts) {
        Map<Integer, List<AiDiagnosticCallDetail.Attempt>> byWorkUnit = new LinkedHashMap<>();
        for (AiDiagnosticCallDetail.Attempt attempt : attempts) {
            byWorkUnit.computeIfAbsent(attempt.workUnitIndex(), ignored -> new ArrayList<>())
                    .add(attempt);
        }
        List<AiDiagnosticCallDetail.CapabilityDetails.Embedding.Batch> batches = new ArrayList<>();
        var contributors = new LinkedHashSet<AiDiagnosticCall.Target>();
        for (var entry : byWorkUnit.entrySet()) {
            List<AiDiagnosticCallDetail.Attempt> unitAttempts = entry.getValue();
            AiDiagnosticCallDetail.Attempt contributor = unitAttempts.stream()
                    .filter(attempt -> "SUCCEEDED".equals(attempt.outcome()))
                    .reduce((first, second) -> second)
                    .orElse(null);
            AiDiagnosticCallDetail.Attempt shape = contributor == null
                    ? unitAttempts.get(unitAttempts.size() - 1)
                    : contributor;
            AiDiagnosticCall.Target actual = contributor == null
                    ? null
                    : target(contributor.providerId(), contributor.deploymentId(),
                            contributor.modelIdentity());
            if (actual != null) {
                contributors.add(actual);
            }
            batches.add(new AiDiagnosticCallDetail.CapabilityDetails.Embedding.Batch(
                    entry.getKey(),
                    shape.inputFromInclusive(),
                    shape.inputToExclusive(),
                    actual,
                    unitAttempts.size(),
                    (int) unitAttempts.stream()
                            .filter(attempt -> "RETRY".equals(attempt.attemptKind())).count(),
                    (int) unitAttempts.stream()
                            .filter(attempt -> "FAILOVER".equals(attempt.attemptKind())).count()));
        }
        boolean succeeded = "SUCCEEDED".equals(row.outcome());
        return new AiDiagnosticCallDetail.CapabilityDetails.Embedding(
                extra.requiredModelIdentity(),
                extra.embeddingInputCount(),
                extra.embeddingBatchCount(),
                extra.embeddingDimensions(),
                row.mixedExecution(),
                List.copyOf(contributors),
                List.copyOf(batches),
                succeeded
                        ? new AiDiagnosticCallDetail.CapabilityDetails.Embedding.Validation(
                                true, true, true)
                        : null);
    }

    private List<AiDiagnosticCallDetail.Candidate> candidates(UUID logicalCallId) {
        return jdbc.query("""
                select candidate_ordinal, provider_id, deployment_id, model_identity, capability,
                       service_tier, decision, planned_order, rejection_reason,
                       default_candidate, priority, breaker_state_snapshot
                  from ai_route_candidate
                 where logical_call_id = ?
                 order by candidate_ordinal
                """,
                (rs, rowNum) -> new AiDiagnosticCallDetail.Candidate(
                        rs.getInt("candidate_ordinal"),
                        rs.getString("provider_id"),
                        rs.getString("deployment_id"),
                        rs.getString("model_identity"),
                        rs.getString("capability"),
                        rs.getString("service_tier"),
                        rs.getString("decision"),
                        integer(rs, "planned_order"),
                        rs.getString("rejection_reason"),
                        rs.getBoolean("default_candidate"),
                        rs.getInt("priority"),
                        rs.getString("breaker_state_snapshot")),
                logicalCallId);
    }

    private List<AiDiagnosticCallDetail.RouteEvent> routeEvents(UUID logicalCallId) {
        return jdbc.query("""
                select sequence_no, occurred_offset_ms, event_type, work_unit_index, attempt_no,
                       candidate_order, provider_id, deployment_id, reason, wait_duration_ms,
                       remaining_budget_ms, eligible_candidate_count
                  from ai_route_event
                 where logical_call_id = ?
                 order by sequence_no
                """,
                (rs, rowNum) -> new AiDiagnosticCallDetail.RouteEvent(
                        rs.getInt("sequence_no"),
                        rs.getLong("occurred_offset_ms"),
                        rs.getString("event_type"),
                        integer(rs, "work_unit_index"),
                        integer(rs, "attempt_no"),
                        integer(rs, "candidate_order"),
                        rs.getString("provider_id"),
                        rs.getString("deployment_id"),
                        rs.getString("reason"),
                        longObject(rs, "wait_duration_ms"),
                        longObject(rs, "remaining_budget_ms"),
                        integer(rs, "eligible_candidate_count")),
                logicalCallId);
    }

    private List<AiDiagnosticCallDetail.Attempt> attempts(UUID logicalCallId) {
        return jdbc.query("""
                select attempt_no, work_unit_index, input_from_inclusive, input_to_exclusive,
                       candidate_order, attempt_kind, started_sequence_no, completed_sequence_no,
                       provider_id, deployment_id, model_identity, breaker_state_before,
                       breaker_state_after, outcome, normalized_error_type, failure_phase,
                       http_status, provider_request_id_masked, started_offset_ms,
                       first_upstream_event_ms, first_meaningful_chunk_ms, downstream_commit_ms,
                       duration_ms, input_token_count, output_token_count, stream_event_count
                  from ai_attempt
                 where logical_call_id = ?
                 order by attempt_no
                """,
                (rs, rowNum) -> new AiDiagnosticCallDetail.Attempt(
                        rs.getInt("attempt_no"),
                        rs.getInt("work_unit_index"),
                        integer(rs, "input_from_inclusive"),
                        integer(rs, "input_to_exclusive"),
                        rs.getInt("candidate_order"),
                        rs.getString("attempt_kind"),
                        rs.getInt("started_sequence_no"),
                        rs.getInt("completed_sequence_no"),
                        rs.getString("provider_id"),
                        rs.getString("deployment_id"),
                        rs.getString("model_identity"),
                        rs.getString("breaker_state_before"),
                        rs.getString("breaker_state_after"),
                        rs.getString("outcome"),
                        rs.getString("normalized_error_type"),
                        rs.getString("failure_phase"),
                        integer(rs, "http_status"),
                        rs.getString("provider_request_id_masked"),
                        rs.getLong("started_offset_ms"),
                        longObject(rs, "first_upstream_event_ms"),
                        longObject(rs, "first_meaningful_chunk_ms"),
                        longObject(rs, "downstream_commit_ms"),
                        rs.getLong("duration_ms"),
                        integer(rs, "input_token_count"),
                        integer(rs, "output_token_count"),
                        integer(rs, "stream_event_count")),
                logicalCallId);
    }

    private Map<UUID, AiDiagnosticCall.Target> lastAttemptTargets(List<UUID> callIds) {
        if (callIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(callIds.size(), "?"));
        Map<UUID, AiDiagnosticCall.Target> targets = new LinkedHashMap<>();
        jdbc.query("""
                select distinct on (logical_call_id)
                       logical_call_id, provider_id, deployment_id, model_identity
                  from ai_attempt
                 where logical_call_id in (%s)
                 order by logical_call_id, attempt_no desc
                """.formatted(placeholders),
                rs -> {
                    targets.put(
                            rs.getObject("logical_call_id", UUID.class),
                            target(
                                    rs.getString("provider_id"),
                                    rs.getString("deployment_id"),
                                    rs.getString("model_identity")));
                },
                callIds.toArray());
        return targets;
    }

    private String where(DiagnosticCallQuery query, Window window, List<Object> args) {
        StringBuilder where = new StringBuilder(" where c.started_at >= ? and c.started_at < ?");
        args.add(window.from());
        args.add(window.to());
        if (query.capability() != null) {
            where.append(" and c.capability = ?");
            args.add(query.capability());
        }
        if (query.outcomes() != null && !query.outcomes().isEmpty()) {
            // 同一字段内取 OR，正好对应总览"失败/流中断"那一格的下钻。
            where.append(" and c.outcome in (")
                    .append(String.join(",", java.util.Collections.nCopies(query.outcomes().size(), "?")))
                    .append(")");
            args.addAll(query.outcomes());
        }
        if (query.tier() != null) {
            where.append(" and c.service_tier = ?");
            args.add(query.tier());
        }
        if (query.failoverOccurred() != null) {
            where.append(query.failoverOccurred() ? " and c.failover_count > 0" : " and c.failover_count = 0");
        }
        if (query.providerId() != null) {
            where.append(" and exists (select 1 from ai_attempt a"
                    + " where a.logical_call_id = c.id and a.provider_id = ?)");
            args.add(query.providerId());
        }
        if (query.requestId() != null) {
            where.append(" and (c.request_id = ? or exists (select 1 from ai_attempt a"
                    + " where a.logical_call_id = c.id and a.provider_request_id_masked = ?))");
            args.add(query.requestId());
            args.add(query.requestId());
        }
        return where.toString();
    }

    private Window window(OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime end = to == null ? now : to;
        OffsetDateTime start = from == null ? end.minus(DEFAULT_WINDOW) : from;
        if (!start.isBefore(end)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FILTER", "from 必须早于 to。");
        }
        if (Duration.between(start, end).compareTo(MAX_WINDOW) > 0) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_FILTER",
                    "时间跨度不能超过 7 天，超出保留期的数据已被清理。");
        }
        return new Window(start, end);
    }

    private static AiDiagnosticCall.Target target(
            String providerId, String deploymentId, String modelIdentity) {
        return providerId == null
                ? null
                : new AiDiagnosticCall.Target(providerId, deploymentId, modelIdentity);
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long longObject(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static OffsetDateTime utc(OffsetDateTime value) {
        return value == null ? null : value.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private record Window(OffsetDateTime from, OffsetDateTime to) {
    }

    /** 详情专用的其余列。列表不查这些，避免每页重复搬运。 */
    private record Extra(
            String requiredModelIdentity,
            String policyVersion,
            String configFingerprint,
            String providerRequestIdMasked,
            String failurePhase,
            String terminationReason,
            Integer httpStatus,
            Long routeDurationMs,
            Long firstUpstreamEventMs,
            Long firstMeaningfulChunkMs,
            Long downstreamCommitMs,
            OffsetDateTime completedAt,
            String finishReason,
            Integer inputTokenCount,
            Integer outputTokenCount,
            Integer embeddingInputCount,
            Integer embeddingBatchCount,
            Integer embeddingDimensions,
            Integer streamEventCount,
            Integer rerankDocumentCount,
            Integer rerankTopN,
            Boolean rerankApplied,
            String degradedReason) {
    }

    private static final RowMapper<Extra> EXTRA_MAPPER = (rs, rowNum) -> new Extra(
            rs.getString("required_model_identity"),
            rs.getString("policy_version"),
            rs.getString("config_fingerprint"),
            rs.getString("provider_request_id_masked"),
            rs.getString("failure_phase"),
            rs.getString("termination_reason"),
            integer(rs, "http_status"),
            longObject(rs, "route_duration_ms"),
            longObject(rs, "first_upstream_event_ms"),
            longObject(rs, "first_meaningful_chunk_ms"),
            longObject(rs, "downstream_commit_ms"),
            utc(rs.getObject("completed_at", OffsetDateTime.class)),
            rs.getString("finish_reason"),
            integer(rs, "input_token_count"),
            integer(rs, "output_token_count"),
            integer(rs, "embedding_input_count"),
            integer(rs, "embedding_batch_count"),
            integer(rs, "embedding_dimensions"),
            integer(rs, "stream_event_count"),
            integer(rs, "rerank_document_count"),
            integer(rs, "rerank_top_n"),
            bool(rs, "rerank_applied"),
            rs.getString("degraded_reason"));

    private static Boolean bool(ResultSet rs, String column) throws SQLException {
        boolean value = rs.getBoolean(column);
        return rs.wasNull() ? null : value;
    }

    private record Row(
            UUID id,
            String requestId,
            String capability,
            String serviceTier,
            String outcome,
            String actualProviderId,
            String actualDeploymentId,
            String actualModelIdentity,
            int attemptCount,
            int retryCount,
            int failoverCount,
            boolean mixedExecution,
            int contributingDeploymentCount,
            boolean committed,
            long totalDurationMs,
            OffsetDateTime startedAt,
            String normalizedErrorType) {

        private AiDiagnosticCall toSummary(AiDiagnosticCall.Target lastAttemptTarget) {
            return new AiDiagnosticCall(
                    id, requestId, capability, serviceTier, outcome,
                    actualProviderId, actualDeploymentId, actualModelIdentity,
                    lastAttemptTarget, attemptCount, retryCount, failoverCount,
                    failoverCount > 0, mixedExecution, contributingDeploymentCount, committed,
                    totalDurationMs, startedAt, normalizedErrorType);
        }
    }

    private static final RowMapper<Row> ROW_MAPPER = (rs, rowNum) -> new Row(
            rs.getObject("id", UUID.class),
            rs.getString("request_id"),
            rs.getString("capability"),
            rs.getString("service_tier"),
            rs.getString("outcome"),
            rs.getString("actual_provider_id"),
            rs.getString("actual_deployment_id"),
            rs.getString("actual_model_identity"),
            rs.getInt("attempt_count"),
            rs.getInt("retry_count"),
            rs.getInt("failover_count"),
            rs.getBoolean("mixed_execution"),
            rs.getInt("contributing_deployment_count"),
            rs.getBoolean("committed"),
            rs.getLong("total_duration_ms"),
            utc(rs.getObject("started_at", OffsetDateTime.class)),
            rs.getString("normalized_error_type"));
}
