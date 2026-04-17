import type {
  AnswerSessionResponse,
  DocumentDetail,
  OpsDiagnosticOrigin,
  OpsDiagnosticSample,
  OpsDiagnosticSampleKind,
  OpsRegressionClass
} from "@xrag/shared-types";

export function buildAnswerSessionSample(input: {
  session: AnswerSessionResponse;
  origin: OpsDiagnosticOrigin;
  severity?: OpsDiagnosticSample["severity"];
  relatedIncidentRef?: string | null;
  relatedDeploymentRecordId?: string | null;
  regressionClass?: OpsRegressionClass | null;
}): OpsDiagnosticSample {
  return {
    sample_id: `answer_session:${input.session.session_id}`,
    sample_kind: "answer_session",
    source_id: input.session.session_id,
    origin: input.origin,
    severity: input.severity ?? getAnswerSessionSeverity(input.session.status),
    detected_at: input.session.updated_at,
    title: "Answer session replay",
    summary: input.session.diagnosis_code
      ? `Session status is ${input.session.status}; diagnosis: ${input.session.diagnosis_code}.`
      : `Session status is ${input.session.status}.`,
    related_incident_ref: input.relatedIncidentRef ?? null,
    related_deployment_record_id: input.relatedDeploymentRecordId ?? null,
    regression_class: input.regressionClass ?? null,
    next_replay_ref: {
      method: "GET",
      path: `/api/v1/ops/replays/answer-sessions/${input.session.session_id}`
    }
  };
}

export function buildDocumentPipelineSample(input: {
  document: DocumentDetail;
  origin: OpsDiagnosticOrigin;
  severity?: OpsDiagnosticSample["severity"];
  relatedIncidentRef?: string | null;
  relatedDeploymentRecordId?: string | null;
  regressionClass?: OpsRegressionClass | null;
}): OpsDiagnosticSample {
  return {
    sample_id: `document:${input.document.id}`,
    sample_kind: "document_pipeline",
    source_id: input.document.id,
    origin: input.origin,
    severity: input.severity ?? getDocumentSeverity(input.document.index_status),
    detected_at: input.document.indexed_at ?? input.document.imported_at,
    title: input.document.title,
    summary: input.document.diagnosis_code
      ? `Document index status is ${input.document.index_status}; diagnosis: ${input.document.diagnosis_code}.`
      : `Document index status is ${input.document.index_status}.`,
    related_incident_ref: input.relatedIncidentRef ?? input.document.last_incident_ref,
    related_deployment_record_id: input.relatedDeploymentRecordId ?? null,
    regression_class: input.regressionClass ?? null,
    next_replay_ref: {
      method: "GET",
      path: `/api/v1/ops/replays/documents/${input.document.id}`
    }
  };
}

export function getEmptyDiagnosticSampleList(input: {
  origin: OpsDiagnosticOrigin;
  window: "24h" | "7d" | "30d";
  page: number;
  pageSize: number;
}) {
  return {
    generated_at: new Date().toISOString(),
    origin: input.origin,
    window: input.window,
    page: input.page,
    page_size: input.pageSize,
    total: 0,
    items: []
  };
}

export function normalizeSampleKind(value: OpsDiagnosticSampleKind | undefined): OpsDiagnosticSampleKind | undefined {
  return value === "answer_session" || value === "document_pipeline" ? value : undefined;
}

function getAnswerSessionSeverity(status: AnswerSessionResponse["status"]): OpsDiagnosticSample["severity"] {
  if (status === "failed") {
    return "high";
  }

  if (status === "needs_scope" || status === "refused") {
    return "medium";
  }

  return "low";
}

function getDocumentSeverity(indexStatus: DocumentDetail["index_status"]): OpsDiagnosticSample["severity"] {
  if (indexStatus === "failed") {
    return "high";
  }

  if (indexStatus === "stale" || indexStatus === "not_indexed") {
    return "medium";
  }

  return "low";
}
