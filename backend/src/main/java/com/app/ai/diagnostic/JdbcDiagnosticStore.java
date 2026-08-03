package com.app.ai.diagnostic;

import com.app.ai.model.ExecutionTarget;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcDiagnosticStore implements DiagnosticStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcDiagnosticStore(JdbcTemplate jdbc, PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void write(CompletedCallDiagnostic diagnostic) {
        transactions.executeWithoutResult(status -> {
            insertCall(diagnostic);
            insertCandidates(diagnostic);
            insertEvents(diagnostic);
            insertAttempts(diagnostic);
        });
    }

    @Override
    public void write(BreakerTransitionDiagnostic transition) {
        jdbc.update("""
                insert into ai_breaker_transition (
                    breaker_key, provider_id, deployment_id, model_identity, capability,
                    from_state, to_state, transition_reason, transitioned_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                transition.key().value(),
                transition.target().providerId().value(),
                transition.target().deploymentId().value(),
                transition.target().modelIdentity().value(),
                transition.key().capability().name(),
                transition.fromState().name(),
                transition.toState().name(),
                transition.reason().name(),
                transition.transitionedAt());
    }

    private void insertCall(CompletedCallDiagnostic call) {
        var execution = call.executionSummary();
        ExecutionTarget actual = execution.actualExecution();
        UsageDiagnostic usage = call.usage();
        TimingDiagnostic timing = call.timing();
        jdbc.update("""
                insert into ai_logical_call (
                    id, request_id, capability, service_tier, required_model_identity, stream,
                    policy_version, config_fingerprint, outcome,
                    actual_provider_id, actual_deployment_id, actual_model_identity,
                    provider_request_id_masked, normalized_error_type, failure_phase,
                    termination_reason, http_status, finish_reason,
                    attempt_count, retry_count, failover_count, mixed_execution,
                    contributing_deployment_count, committed, rerank_applied, degraded_reason,
                    input_token_count, output_token_count, embedding_input_count,
                    embedding_batch_count, embedding_dimensions, rerank_document_count,
                    rerank_top_n, stream_event_count, route_duration_ms,
                    first_upstream_event_ms, first_meaningful_chunk_ms, downstream_commit_ms,
                    total_duration_ms, started_at, completed_at)
                values (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                call.logicalCallId(),
                call.requestId(),
                call.capability().name(),
                name(call.serviceTier()),
                value(call.requiredModelIdentity()),
                call.stream(),
                call.policyVersion(),
                call.configFingerprint(),
                call.outcome().name(),
                actual == null ? null : actual.providerId().value(),
                actual == null ? null : actual.deploymentId().value(),
                actual == null ? null : actual.modelIdentity().value(),
                execution.providerRequestIdMasked(),
                name(call.normalizedErrorType()),
                name(call.failurePhase()),
                call.terminationReason().name(),
                call.httpStatus(),
                name(call.finishReason()),
                call.attempts().size(),
                call.retryCount(),
                call.failoverCount(),
                execution.mixedExecution(),
                execution.contributingDeploymentCount(),
                call.committed(),
                call.rerankApplied(),
                name(call.degradedReason()),
                usage.inputTokenCount(),
                usage.outputTokenCount(),
                usage.embeddingInputCount(),
                usage.embeddingBatchCount(),
                usage.embeddingDimensions(),
                usage.rerankDocumentCount(),
                usage.rerankTopN(),
                usage.streamEventCount(),
                timing.routeDurationMs(),
                timing.firstUpstreamEventMs(),
                timing.firstMeaningfulChunkMs(),
                timing.downstreamCommitMs(),
                timing.totalDurationMs(),
                timing.startedAt(),
                timing.completedAt());
    }

    private void insertCandidates(CompletedCallDiagnostic call) {
        List<Object[]> rows = call.candidates().stream()
                .map(candidate -> new Object[] {
                    call.logicalCallId(),
                    candidate.candidateOrdinal(),
                    candidate.target().providerId().value(),
                    candidate.target().deploymentId().value(),
                    candidate.target().modelIdentity().value(),
                    candidate.capability().name(),
                    name(candidate.serviceTier()),
                    candidate.quotaDomain().value(),
                    candidate.decision().name(),
                    name(candidate.rejectionReason()),
                    candidate.plannedOrder(),
                    candidate.defaultCandidate(),
                    candidate.priority(),
                    name(candidate.breakerStateSnapshot())
                })
                .toList();
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    insert into ai_route_candidate (
                        logical_call_id, candidate_ordinal, provider_id, deployment_id,
                        model_identity, capability, service_tier, quota_domain, decision,
                        rejection_reason, planned_order, default_candidate, priority,
                        breaker_state_snapshot)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, rows);
        }
    }

    private void insertEvents(CompletedCallDiagnostic call) {
        List<Object[]> rows = call.routeEvents().stream()
                .map(event -> new Object[] {
                    call.logicalCallId(),
                    event.sequenceNo(),
                    event.occurredOffsetMs(),
                    event.eventType().name(),
                    event.workUnitIndex(),
                    event.attemptNo(),
                    event.candidateOrder(),
                    value(event.providerId()),
                    value(event.deploymentId()),
                    event.reason(),
                    event.waitDurationMs(),
                    event.remainingBudgetMs(),
                    event.eligibleCandidateCount()
                })
                .toList();
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    insert into ai_route_event (
                        logical_call_id, sequence_no, occurred_offset_ms, event_type,
                        work_unit_index, attempt_no, candidate_order, provider_id, deployment_id,
                        reason, wait_duration_ms, remaining_budget_ms, eligible_candidate_count)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, rows);
        }
    }

    private void insertAttempts(CompletedCallDiagnostic call) {
        List<Object[]> rows = call.attempts().stream()
                .map(attempt -> new Object[] {
                    call.logicalCallId(),
                    attempt.attemptNo(),
                    attempt.workUnitIndex(),
                    attempt.inputFromInclusive(),
                    attempt.inputToExclusive(),
                    attempt.candidateOrder(),
                    attempt.attemptKind().name(),
                    attempt.startedSequenceNo(),
                    attempt.completedSequenceNo(),
                    attempt.target().providerId().value(),
                    attempt.target().deploymentId().value(),
                    attempt.target().modelIdentity().value(),
                    attempt.quotaDomain().value(),
                    attempt.breakerStateBefore().name(),
                    name(attempt.breakerStateAfter()),
                    attempt.outcome().name(),
                    name(attempt.normalizedErrorType()),
                    name(attempt.failurePhase()),
                    attempt.httpStatus(),
                    attempt.providerRequestIdMasked(),
                    attempt.startedOffsetMs(),
                    attempt.firstUpstreamEventMs(),
                    attempt.firstMeaningfulChunkMs(),
                    attempt.downstreamCommitMs(),
                    attempt.durationMs(),
                    attempt.inputTokenCount(),
                    attempt.outputTokenCount(),
                    attempt.streamEventCount()
                })
                .toList();
        if (!rows.isEmpty()) {
            jdbc.batchUpdate("""
                    insert into ai_attempt (
                        logical_call_id, attempt_no, work_unit_index, input_from_inclusive,
                        input_to_exclusive, candidate_order, attempt_kind, started_sequence_no,
                        completed_sequence_no, provider_id, deployment_id, model_identity,
                        quota_domain, breaker_state_before, breaker_state_after, outcome,
                        normalized_error_type, failure_phase, http_status,
                        provider_request_id_masked, started_offset_ms, first_upstream_event_ms,
                        first_meaningful_chunk_ms, downstream_commit_ms, duration_ms,
                        input_token_count, output_token_count, stream_event_count)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, rows);
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String value(Object stableId) {
        return stableId == null ? null : stableId.toString();
    }
}
