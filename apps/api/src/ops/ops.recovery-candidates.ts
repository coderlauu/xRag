import type {
  AnswerSessionResponse,
  DocumentDetail,
  OpsDiagnosticSample,
  OpsRecoveryActionPreviewRequest,
  OpsRecoveryActionPreviewResponse,
  OpsRecoveryActionType,
  OpsRecoveryCandidate,
  OpsRecoveryCandidateSourceType,
  OpsRecoveryFactSnapshot,
  OpsRecoveryPrecondition,
  OpsRecoveryRecommendationState,
  OpsRecoveryRiskLevel,
  OpsRecoveryTargetRef,
  OpsRecoveryTargetType
} from "@xrag/shared-types";

export type RecoveryCandidateBuildInput = {
  sample: OpsDiagnosticSample;
  sourceType: OpsRecoveryCandidateSourceType;
  sourceRef?: string;
  document?: DocumentDetail | null;
};

export function buildRecoveryCandidate(input: RecoveryCandidateBuildInput): OpsRecoveryCandidate {
  if (input.sample.sample_kind === "answer_session") {
    return buildAnswerDiagnosticCandidate(input.sample, input.sourceType, input.sourceRef);
  }

  return buildDocumentRecoveryCandidate(input.sample, input.sourceType, input.sourceRef, input.document ?? null);
}

export function filterRecoveryCandidates(
  candidates: OpsRecoveryCandidate[],
  filters: {
    actionType?: OpsRecoveryActionType;
    riskLevel?: OpsRecoveryRiskLevel;
    recommendationState?: OpsRecoveryRecommendationState;
  }
) {
  return candidates.filter((candidate) => {
    if (filters.actionType && candidate.action_type !== filters.actionType) {
      return false;
    }

    if (filters.riskLevel && candidate.risk_level !== filters.riskLevel) {
      return false;
    }

    if (filters.recommendationState && candidate.recommendation_state !== filters.recommendationState) {
      return false;
    }

    return true;
  });
}

export function buildDocumentRecoveryFactSnapshot(input: {
  document: DocumentDetail;
  actionType: OpsRecoveryActionType;
  capturedAt: Date;
}): OpsRecoveryFactSnapshot {
  return {
    captured_at: input.capturedAt.toISOString(),
    target_type: "document",
    target_refs: [{ type: "document", id: input.document.id }],
    facts: {
      action_type: input.actionType,
      document_id: input.document.id,
      title: input.document.title,
      parse_status: input.document.parse_status,
      index_status: input.document.index_status,
      citation_ready: input.document.citation_ready,
      diagnosis_code: input.document.diagnosis_code,
      latest_job_status: input.document.latest_job_status,
      latest_job_id: input.document.latest_job?.id ?? null,
      last_incident_ref: input.document.last_incident_ref,
      imported_at: input.document.imported_at,
      created_at: input.document.created_at
    }
  };
}

export function buildAnswerRecoveryFactSnapshot(input: {
  session: AnswerSessionResponse;
  actionType: OpsRecoveryActionType;
  capturedAt: Date;
}): OpsRecoveryFactSnapshot {
  return {
    captured_at: input.capturedAt.toISOString(),
    target_type: "answer_session",
    target_refs: [{ type: "answer_session", id: input.session.session_id }],
    facts: {
      action_type: input.actionType,
      session_id: input.session.session_id,
      status: input.session.status,
      diagnosis_code: input.session.diagnosis_code,
      retrieval_mode: input.session.retrieval_mode,
      citation_count: input.session.citations.length,
      evidence_group_count: input.session.evidence_groups.length,
      updated_at: input.session.updated_at
    }
  };
}

export function buildRecoveryPreview(input: {
  request: OpsRecoveryActionPreviewRequest;
  generatedAt: Date;
  idempotencyKey: string;
  sourceFacts: OpsRecoveryFactSnapshot;
  beforeFacts: OpsRecoveryFactSnapshot;
  preconditions: OpsRecoveryPrecondition[];
}): OpsRecoveryActionPreviewResponse {
  const expiresAt = new Date(input.generatedAt.getTime() + 15 * 60 * 1000);
  const recommendationState = getRecommendationState(input.preconditions);

  return {
    preview_id: `preview:${input.idempotencyKey.slice(0, 32)}`,
    generated_at: input.generatedAt.toISOString(),
    expires_at: expiresAt.toISOString(),
    candidate_id: input.request.candidate_id,
    action_type: input.request.action_type,
    target_type: input.request.target_type,
    target_refs: input.request.target_refs,
    risk_level: getPreviewRiskLevel(input.request.action_type, input.beforeFacts),
    recommendation_state: recommendationState,
    preconditions: input.preconditions,
    blocked_reason: getBlockedReason(input.preconditions),
    estimated_blast_radius: getEstimatedBlastRadius(input.request.action_type, input.request.target_refs),
    idempotency_key: input.idempotencyKey,
    source_facts: input.sourceFacts,
    before_facts: input.beforeFacts
  };
}

export function getDocumentPreviewPreconditions(
  actionType: OpsRecoveryActionType,
  document: DocumentDetail
): OpsRecoveryPrecondition[] {
  const activeIndexing = document.index_status === "queued" || document.index_status === "chunking" || document.index_status === "embedding";
  const canRetry = document.parse_status === "failed" || document.latest_job_status === "failed";
  const canReindex = document.index_status === "failed" || document.index_status === "stale" || !document.citation_ready;

  if (actionType === "document_retry") {
    return [
      {
        code: "document_exists",
        label: "Document fact is available",
        satisfied: true,
        detail: document.title
      },
      {
        code: "document_has_retryable_failure",
        label: "Document has a retryable failed pipeline step",
        satisfied: canRetry,
        detail: canRetry ? document.diagnosis_code : "Document is not currently in a failed retry state."
      },
      {
        code: "no_active_indexing_job",
        label: "No active indexing job is running",
        satisfied: !activeIndexing,
        detail: activeIndexing ? `Current index status is ${document.index_status}.` : null
      }
    ];
  }

  return [
    {
      code: "document_exists",
      label: "Document fact is available",
      satisfied: true,
      detail: document.title
    },
    {
      code: "document_needs_reindex",
      label: "Document needs indexing recovery",
      satisfied: canReindex,
      detail: canReindex ? document.diagnosis_code : "Document is already indexed and citation-ready."
    },
    {
      code: "no_active_indexing_job",
      label: "No active indexing job is running",
      satisfied: !activeIndexing,
      detail: activeIndexing ? `Current index status is ${document.index_status}.` : null
    }
  ];
}

export function getAnswerPreviewPreconditions(session: AnswerSessionResponse): OpsRecoveryPrecondition[] {
  const terminal = session.status === "answered" || session.status === "needs_scope" || session.status === "refused" || session.status === "failed";

  return [
    {
      code: "answer_session_exists",
      label: "Answer session fact is available",
      satisfied: true,
      detail: session.question
    },
    {
      code: "answer_session_terminal",
      label: "Answer session is terminal",
      satisfied: terminal,
      detail: terminal ? session.status : "Active answer sessions are not replayed by recovery preview."
    },
    {
      code: "read_only_diagnostic_rerun",
      label: "Diagnostic rerun is read-only",
      satisfied: true,
      detail: "Preview does not replace the user-visible answer."
    }
  ];
}

function buildAnswerDiagnosticCandidate(
  sample: OpsDiagnosticSample,
  sourceType: OpsRecoveryCandidateSourceType,
  sourceRef?: string
): OpsRecoveryCandidate {
  const targetRef: OpsRecoveryTargetRef = { type: "answer_session", id: sample.source_id };

  return {
    candidate_id: buildCandidateId(sourceType, "answer_diagnostic_rerun", "answer_session", sample.source_id),
    source_type: sourceType,
    source_ref: sourceRef ?? sample.sample_id,
    action_type: "answer_diagnostic_rerun",
    target_type: "answer_session",
    target_refs: [targetRef],
    risk_level: severityToRisk(sample.severity),
    recommendation_state: sample.severity === "low" ? "available" : "recommended",
    title: "Run answer diagnostic replay",
    summary: `Replay answer diagnostics for ${sample.sample_id}: ${sample.summary}`,
    preconditions: [
      {
        code: "source_sample_available",
        label: "Source diagnostic sample is available",
        satisfied: true,
        detail: sample.sample_id
      },
      {
        code: "read_only_diagnostic_rerun",
        label: "Diagnostic rerun is read-only",
        satisfied: true,
        detail: "The rerun will not replace the user-visible answer."
      }
    ],
    blocked_reason: null,
    preview_ref: {
      method: "GET",
      path: `/api/v1/ops/replays/answer-sessions/${sample.source_id}`
    }
  };
}

function buildDocumentRecoveryCandidate(
  sample: OpsDiagnosticSample,
  sourceType: OpsRecoveryCandidateSourceType,
  sourceRef: string | undefined,
  document: DocumentDetail | null
): OpsRecoveryCandidate {
  const targetRef: OpsRecoveryTargetRef = { type: "document", id: sample.source_id };
  const actionType = getDocumentCandidateActionType(document);
  const preconditions = getDocumentCandidatePreconditions(sample, document, actionType);
  const blockedReason = getBlockedReason(preconditions);

  return {
    candidate_id: buildCandidateId(sourceType, actionType, "document", sample.source_id),
    source_type: sourceType,
    source_ref: sourceRef ?? sample.sample_id,
    action_type: actionType,
    target_type: "document",
    target_refs: [targetRef],
    risk_level: severityToRisk(sample.severity),
    recommendation_state: blockedReason ? "blocked" : sample.severity === "low" ? "available" : "recommended",
    title: actionType === "document_retry" ? "Retry document pipeline" : "Reindex document",
    summary: `${actionType === "document_retry" ? "Retry" : "Reindex"} ${sample.sample_id}: ${sample.summary}`,
    preconditions,
    blocked_reason: blockedReason,
    preview_ref: {
      method: "GET",
      path: `/api/v1/ops/replays/documents/${sample.source_id}`
    }
  };
}

function getDocumentCandidateActionType(document: DocumentDetail | null): OpsRecoveryActionType {
  if (document?.parse_status === "failed" || document?.latest_job_status === "failed") {
    return "document_retry";
  }

  return "document_reindex";
}

function getDocumentCandidatePreconditions(
  sample: OpsDiagnosticSample,
  document: DocumentDetail | null,
  actionType: OpsRecoveryActionType
): OpsRecoveryPrecondition[] {
  if (!document) {
    return [
      {
        code: "document_fact_available",
        label: "Document fact is available",
        satisfied: false,
        detail: `Could not load document ${sample.source_id}.`
      }
    ];
  }

  return [
    {
      code: "source_sample_available",
      label: "Source diagnostic sample is available",
      satisfied: true,
      detail: sample.sample_id
    },
    ...getDocumentPreviewPreconditions(actionType, document)
  ];
}

function getRecommendationState(preconditions: OpsRecoveryPrecondition[]): OpsRecoveryRecommendationState {
  return preconditions.every((precondition) => precondition.satisfied) ? "available" : "blocked";
}

function getBlockedReason(preconditions: OpsRecoveryPrecondition[]) {
  const failed = preconditions.find((precondition) => !precondition.satisfied);
  return failed ? `${failed.code}: ${failed.detail ?? failed.label}` : null;
}

function getPreviewRiskLevel(actionType: OpsRecoveryActionType, beforeFacts: OpsRecoveryFactSnapshot): OpsRecoveryRiskLevel {
  if (actionType === "answer_diagnostic_rerun") {
    return "low";
  }

  if (beforeFacts.facts.index_status === "failed" || beforeFacts.facts.parse_status === "failed") {
    return "medium";
  }

  return "low";
}

function getEstimatedBlastRadius(actionType: OpsRecoveryActionType, targetRefs: OpsRecoveryTargetRef[]) {
  if (actionType === "answer_diagnostic_rerun") {
    return `${targetRefs.length} answer session diagnostic replay; no user-visible answer replacement.`;
  }

  return `${targetRefs.length} document pipeline target; downstream indexing jobs may be enqueued after operator approval.`;
}

function buildCandidateId(
  sourceType: OpsRecoveryCandidateSourceType,
  actionType: OpsRecoveryActionType,
  targetType: OpsRecoveryTargetType,
  targetId: string
) {
  return `${sourceType}:${actionType}:${targetType}:${targetId}`;
}

function severityToRisk(severity: OpsDiagnosticSample["severity"]): OpsRecoveryRiskLevel {
  if (severity === "high") {
    return "high";
  }

  if (severity === "medium") {
    return "medium";
  }

  return "low";
}
