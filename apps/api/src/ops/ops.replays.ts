import type {
  AnswerRetrievalTraceResponse,
  AnswerSessionResponse,
  DocumentDetail,
  DocumentEvidenceResponse,
  DocumentTimelineResponse,
  OpsAnswerSessionReplayResponse,
  OpsDocumentReplayResponse,
  OpsReadinessBlockingReason
} from "@xrag/shared-types";
import { buildAnswerSessionSample, buildDocumentPipelineSample } from "./ops.diagnostic-samples";

export function buildAnswerSessionReplayResponse(input: {
  session: AnswerSessionResponse;
  retrieval: AnswerRetrievalTraceResponse;
  relatedIncidentRef?: string | null;
  relatedDeploymentRecordId?: string | null;
  relatedEvaluationRunRef?: string | null;
}): OpsAnswerSessionReplayResponse {
  return {
    generated_at: new Date().toISOString(),
    sample: buildAnswerSessionSample({
      session: input.session,
      origin: "trend",
      relatedIncidentRef: input.relatedIncidentRef ?? null,
      relatedDeploymentRecordId: input.relatedDeploymentRecordId ?? null
    }),
    session: input.session,
    retrieval: input.retrieval,
    related_context: {
      related_incident_ref: input.relatedIncidentRef ?? null,
      related_deployment_record_id: input.relatedDeploymentRecordId ?? null,
      related_evaluation_run_ref: input.relatedEvaluationRunRef ?? null,
      freshness_flags: getAnswerFreshnessFlags(input.session, input.retrieval)
    }
  };
}

export function buildDocumentReplayResponse(input: {
  document: DocumentDetail;
  timeline: DocumentTimelineResponse;
  evidence: DocumentEvidenceResponse;
  relatedAnswerSessionCount?: number;
  relatedDeploymentRecordId?: string | null;
}): OpsDocumentReplayResponse {
  return {
    generated_at: new Date().toISOString(),
    sample: buildDocumentPipelineSample({
      document: input.document,
      origin: "trend"
    }),
    document: input.document,
    timeline: input.timeline,
    evidence: input.evidence,
    related_context: {
      blocking_reason: getDocumentBlockingReason(input.document),
      related_incident_ref: input.document.last_incident_ref,
      related_answer_session_count: input.relatedAnswerSessionCount ?? 0,
      related_deployment_record_id: input.relatedDeploymentRecordId ?? null
    }
  };
}

function getAnswerFreshnessFlags(session: AnswerSessionResponse, retrieval: AnswerRetrievalTraceResponse) {
  const flags: OpsAnswerSessionReplayResponse["related_context"]["freshness_flags"] = [];

  if (session.status === "needs_scope" || session.diagnosis_code === "retrieval_scope_empty") {
    flags.push("retrieval_scope_empty");
  }

  if (retrieval.items.some((item) => item.exclusion_reason === "citation_unready")) {
    flags.push("citation_unready");
  }

  if (session.evidence_groups.some((group) => group.freshness_badge === "stale_risk")) {
    flags.push("stale_document");
  }

  return flags;
}

function getDocumentBlockingReason(document: DocumentDetail): OpsReadinessBlockingReason | null {
  if (document.index_status === "failed") {
    return "indexing_failed";
  }

  if (document.index_status === "queued" || document.index_status === "chunking" || document.index_status === "embedding") {
    return "indexing_backlog";
  }

  if (document.index_status === "stale") {
    return "stale_corpus";
  }

  if (document.index_status !== "ready" || !document.citation_ready) {
    return "no_ready_documents";
  }

  return null;
}
