import type {
  AnswerSessionStatus,
  AnswerSessionResponse,
  DocumentDetail,
  IndexStatus,
  IncidentSeverity,
  IncidentSource,
  IncidentStatus,
  OpsDiagnosticOrigin,
  OpsDiagnosticSample,
  OpsDiagnosticSampleKind,
  OpsDiagnosticSampleListResponse,
  OpsRegressionClass
} from "@xrag/shared-types";
import { and, desc, eq, inArray, sql } from "drizzle-orm";
import type { SQL } from "drizzle-orm";
import type { DatabaseClient } from "../database/database.service";
import { answerSessions, deploymentRecords, documentParseJobs, documents, uploads } from "../database/schema";

export type DiagnosticSampleCandidate = {
  sample: OpsDiagnosticSample;
  detectedAtMs: number;
  shapeKey: string;
};

type SampleListInput = {
  origin: OpsDiagnosticOrigin;
  window: "24h" | "7d" | "30d";
  page: number;
  pageSize: number;
  sampleKind?: OpsDiagnosticSampleKind;
  clusterKey?: string;
  deploymentRecordId?: string;
};

type SampleWindowInput = {
  startAt: Date;
  endAt: Date;
  origin: OpsDiagnosticOrigin;
  sampleKind?: OpsDiagnosticSampleKind;
  relatedDeploymentRecordId?: string | null;
};

type AnswerSampleRow = {
  id: string;
  question: string;
  status: AnswerSessionStatus;
  diagnosisCode: string | null;
  createdAt: Date;
  updatedAt: Date;
  finishedAt: Date | null;
  eventAt: Date;
};

type DocumentSampleRow = {
  id: string;
  title: string;
  indexStatus: IndexStatus;
  citationReady: boolean;
  diagnosisCode: string | null;
  lastIncidentRef: string | null;
  createdAt: Date;
  importedAt: Date;
  updatedAt: Date;
  indexedAt: Date | null;
  eventAt: Date;
};

type JobIncidentSampleRow = DocumentSampleRow & {
  jobId: string;
  jobType: string;
  jobStatus: string;
  jobDiagnosisCode: string | null;
  jobIncidentRef: string | null;
  jobFinishedAt: Date | null;
  jobCreatedAt: Date;
};

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

export async function listDiagnosticSamplesFromDb(
  db: DatabaseClient,
  input: SampleListInput
): Promise<OpsDiagnosticSampleListResponse> {
  if (input.origin === "incident_cluster") {
    const candidates = await listIncidentClusterSamples(db, input);
    return toSampleListResponse(input, candidates);
  }

  if (input.origin === "release_compare" && input.deploymentRecordId) {
    const [deployment] = await db
      .select({
        deployedAt: deploymentRecords.deployedAt
      })
      .from(deploymentRecords)
      .where(eq(deploymentRecords.id, input.deploymentRecordId))
      .limit(1);

    if (!deployment) {
      return getEmptyDiagnosticSampleList(input);
    }

    const { beforeStart, beforeEnd, afterStart, afterEnd } = getDeploymentSampleWindows(
      deployment.deployedAt,
      input.window
    );
    const [beforeSamples, afterSamples] = await Promise.all([
      listRiskSamplesForWindow(db, {
        startAt: beforeStart,
        endAt: beforeEnd,
        origin: "release_compare",
        sampleKind: input.sampleKind,
        relatedDeploymentRecordId: input.deploymentRecordId
      }),
      listRiskSamplesForWindow(db, {
        startAt: afterStart,
        endAt: afterEnd,
        origin: "release_compare",
        sampleKind: input.sampleKind,
        relatedDeploymentRecordId: input.deploymentRecordId
      })
    ]);

    return toSampleListResponse(input, classifyDeploymentCandidates({ beforeSamples, afterSamples }));
  }

  const { startAt, endAt } = getRelativeWindow(input.window);
  const candidates = await listRiskSamplesForWindow(db, {
    startAt,
    endAt,
    origin: input.origin,
    sampleKind: input.sampleKind,
    relatedDeploymentRecordId: input.deploymentRecordId ?? null
  });

  return toSampleListResponse(input, candidates);
}

export async function listRiskSamplesForWindow(
  db: DatabaseClient,
  input: SampleWindowInput
): Promise<DiagnosticSampleCandidate[]> {
  const [answerCandidates, documentCandidates] = await Promise.all([
    input.sampleKind && input.sampleKind !== "answer_session"
      ? Promise.resolve([])
      : listAnswerSessionCandidates(db, input),
    input.sampleKind && input.sampleKind !== "document_pipeline"
      ? Promise.resolve([])
      : listDocumentPipelineCandidates(db, input)
  ]);

  return sortCandidates([...answerCandidates, ...documentCandidates]);
}

export function classifyDeploymentSamples(input: {
  beforeSamples: DiagnosticSampleCandidate[];
  afterSamples: DiagnosticSampleCandidate[];
}): OpsDiagnosticSample[] {
  return classifyDeploymentCandidates(input).map((candidate) => candidate.sample);
}

export function classifyDeploymentCandidates(input: {
  beforeSamples: DiagnosticSampleCandidate[];
  afterSamples: DiagnosticSampleCandidate[];
}): DiagnosticSampleCandidate[] {
  const beforeSourceIds = new Set(input.beforeSamples.map((candidate) => candidate.sample.source_id));
  const beforeShapeKeys = new Set(input.beforeSamples.map((candidate) => candidate.shapeKey));

  return input.afterSamples.map((candidate) => ({
    ...candidate,
    sample: {
      ...candidate.sample,
      regression_class: beforeSourceIds.has(candidate.sample.source_id) || beforeShapeKeys.has(candidate.shapeKey)
        ? "existing_debt"
        : "new_regression"
    }
  }));
}

export function normalizeSampleKind(value: OpsDiagnosticSampleKind | undefined): OpsDiagnosticSampleKind | undefined {
  return value === "answer_session" || value === "document_pipeline" ? value : undefined;
}

export function getRelativeWindow(window: "24h" | "7d" | "30d", now = new Date()) {
  const endAt = new Date(now);
  const startAt = new Date(now);

  switch (window) {
    case "30d":
      startAt.setDate(startAt.getDate() - 30);
      break;
    case "7d":
      startAt.setDate(startAt.getDate() - 7);
      break;
    case "24h":
    default:
      startAt.setHours(startAt.getHours() - 24);
      break;
  }

  return { startAt, endAt };
}

function getDeploymentSampleWindows(deployedAt: Date, window: "24h" | "7d" | "30d") {
  const durationMs = getWindowDurationMs(window);

  return {
    beforeStart: new Date(deployedAt.getTime() - durationMs),
    beforeEnd: new Date(deployedAt),
    afterStart: new Date(deployedAt),
    afterEnd: new Date(deployedAt.getTime() + durationMs)
  };
}

function getWindowDurationMs(window: "24h" | "7d" | "30d") {
  switch (window) {
    case "30d":
      return 30 * 24 * 60 * 60 * 1000;
    case "7d":
      return 7 * 24 * 60 * 60 * 1000;
    case "24h":
    default:
      return 24 * 60 * 60 * 1000;
  }
}

function toSampleListResponse(
  input: SampleListInput,
  candidates: DiagnosticSampleCandidate[]
): OpsDiagnosticSampleListResponse {
  const sorted = sortCandidates(candidates);
  const offset = (input.page - 1) * input.pageSize;

  return {
    generated_at: new Date().toISOString(),
    origin: input.origin,
    window: input.window,
    page: input.page,
    page_size: input.pageSize,
    total: sorted.length,
    items: sorted.slice(offset, offset + input.pageSize).map((candidate) => candidate.sample)
  };
}

async function listAnswerSessionCandidates(
  db: DatabaseClient,
  input: SampleWindowInput
): Promise<DiagnosticSampleCandidate[]> {
  const eventAtExpr = getAnswerEventAtExpression();
  const rows = await db
    .select({
      id: answerSessions.id,
      question: answerSessions.question,
      status: answerSessions.status,
      diagnosisCode: answerSessions.diagnosisCode,
      createdAt: answerSessions.createdAt,
      updatedAt: answerSessions.updatedAt,
      finishedAt: answerSessions.finishedAt,
      eventAt: eventAtExpr
    })
    .from(answerSessions)
    .where(
      and(
        sql`${eventAtExpr} >= ${input.startAt}`,
        sql`${eventAtExpr} <= ${input.endAt}`,
        inArray(answerSessions.status, ["failed", "needs_scope", "refused"])
      )
    )
    .orderBy(desc(eventAtExpr), desc(answerSessions.updatedAt));

  return rows.map((row) => buildAnswerSessionCandidate(row, input.origin, input.relatedDeploymentRecordId ?? null));
}

async function listDocumentPipelineCandidates(
  db: DatabaseClient,
  input: SampleWindowInput
): Promise<DiagnosticSampleCandidate[]> {
  const eventAtExpr = getDocumentEventAtExpression();
  const rows = await db
    .select({
      id: documents.id,
      title: documents.title,
      indexStatus: documents.indexStatus,
      citationReady: documents.citationReady,
      diagnosisCode: documents.diagnosisCode,
      lastIncidentRef: documents.lastIncidentRef,
      createdAt: documents.createdAt,
      importedAt: documents.importedAt,
      updatedAt: documents.updatedAt,
      indexedAt: documents.indexedAt,
      eventAt: eventAtExpr
    })
    .from(documents)
    .where(
      and(
        sql`${eventAtExpr} >= ${input.startAt}`,
        sql`${eventAtExpr} <= ${input.endAt}`,
        sql`(${documents.indexStatus} in ('failed', 'stale', 'not_indexed', 'queued', 'chunking', 'embedding') or ${documents.citationReady} = false)`
      )
    )
    .orderBy(desc(eventAtExpr), desc(documents.updatedAt));

  return rows.map((row) => buildDocumentPipelineCandidate(row, input.origin, input.relatedDeploymentRecordId ?? null));
}

async function listIncidentClusterSamples(
  db: DatabaseClient,
  input: SampleListInput
): Promise<DiagnosticSampleCandidate[]> {
  if (input.sampleKind && input.sampleKind !== "document_pipeline") {
    return [];
  }

  const { startAt, endAt } = getRelativeWindow(input.window);
  const jobRows = await listJobIncidentDocumentRows(db, startAt, endAt);
  const uploadRows = await listUploadIncidentDocumentRows(db, startAt, endAt);
  const candidates: DiagnosticSampleCandidate[] = [];

  for (const row of jobRows) {
    const source = resolveIncidentSource(row.jobType);
    const severity = getIncidentSeverity(row.jobDiagnosisCode, source);
    const status = getIncidentStatus(row.jobStatus);
    const clusterKey = `${source}:${severity}:${status}`;

    if (clusterKey !== input.clusterKey) {
      continue;
    }

    candidates.push(
      buildDocumentPipelineCandidate(
        row,
        "incident_cluster",
        null,
        severity,
        row.jobIncidentRef || buildIncidentRef("JOB", row.jobId)
      )
    );
  }

  for (const row of uploadRows) {
    const severity = getIncidentSeverity(row.uploadErrorCode, "upload");
    const clusterKey = `upload:${severity}:open`;

    if (clusterKey !== input.clusterKey || !row.documentId) {
      continue;
    }

    candidates.push(
      buildDocumentPipelineCandidate(
        {
          id: row.documentId,
          title: row.documentTitle ?? row.uploadFileName,
          indexStatus: row.documentIndexStatus ?? "not_indexed",
          citationReady: row.documentCitationReady ?? false,
          diagnosisCode: row.documentDiagnosisCode ?? row.uploadErrorCode,
          lastIncidentRef: row.documentLastIncidentRef,
          createdAt: row.uploadCreatedAt,
          importedAt: row.documentImportedAt ?? row.uploadCreatedAt,
          updatedAt: row.documentUpdatedAt ?? row.uploadCreatedAt,
          indexedAt: row.documentIndexedAt,
          eventAt: row.uploadEventAt
        },
        "incident_cluster",
        null,
        severity,
        buildIncidentRef("UPL", row.uploadId)
      )
    );
  }

  return sortCandidates(candidates);
}

async function listJobIncidentDocumentRows(db: DatabaseClient, startAt: Date, endAt: Date): Promise<JobIncidentSampleRow[]> {
  const eventAtExpr = sql<Date>`coalesce(${documentParseJobs.finishedAt}, ${documentParseJobs.createdAt})`;

  return db
    .select({
      id: documents.id,
      title: documents.title,
      indexStatus: documents.indexStatus,
      citationReady: documents.citationReady,
      diagnosisCode: documents.diagnosisCode,
      lastIncidentRef: documents.lastIncidentRef,
      createdAt: documents.createdAt,
      importedAt: documents.importedAt,
      updatedAt: documents.updatedAt,
      indexedAt: documents.indexedAt,
      eventAt: eventAtExpr,
      jobId: documentParseJobs.id,
      jobType: documentParseJobs.jobType,
      jobStatus: documentParseJobs.status,
      jobDiagnosisCode: documentParseJobs.diagnosisCode,
      jobIncidentRef: documentParseJobs.incidentRef,
      jobFinishedAt: documentParseJobs.finishedAt,
      jobCreatedAt: documentParseJobs.createdAt
    })
    .from(documentParseJobs)
    .innerJoin(documents, eq(documents.id, documentParseJobs.documentId))
    .where(
      and(
        inArray(documentParseJobs.status, ["failed", "dead"]),
        sql`${eventAtExpr} >= ${startAt}`,
        sql`${eventAtExpr} <= ${endAt}`
      )
    )
    .orderBy(desc(eventAtExpr), desc(documentParseJobs.createdAt));
}

async function listUploadIncidentDocumentRows(db: DatabaseClient, startAt: Date, endAt: Date) {
  const eventAtExpr = sql<Date>`coalesce(${uploads.completedAt}, ${uploads.createdAt})`;

  return db
    .select({
      uploadId: uploads.id,
      uploadFileName: uploads.fileName,
      uploadErrorCode: uploads.errorCode,
      uploadCreatedAt: uploads.createdAt,
      uploadEventAt: eventAtExpr,
      documentId: documents.id,
      documentTitle: documents.title,
      documentIndexStatus: documents.indexStatus,
      documentCitationReady: documents.citationReady,
      documentDiagnosisCode: documents.diagnosisCode,
      documentLastIncidentRef: documents.lastIncidentRef,
      documentImportedAt: documents.importedAt,
      documentUpdatedAt: documents.updatedAt,
      documentIndexedAt: documents.indexedAt
    })
    .from(uploads)
    .leftJoin(documents, eq(documents.uploadId, uploads.id))
    .where(and(eq(uploads.status, "failed"), sql`${eventAtExpr} >= ${startAt}`, sql`${eventAtExpr} <= ${endAt}`))
    .orderBy(desc(eventAtExpr), desc(uploads.createdAt));
}

function buildAnswerSessionCandidate(
  row: AnswerSampleRow,
  origin: OpsDiagnosticOrigin,
  relatedDeploymentRecordId: string | null
): DiagnosticSampleCandidate {
  const eventAt = toDate(row.eventAt);
  const severity = getAnswerSessionSeverity(row.status);
  const sample: OpsDiagnosticSample = {
    sample_id: `answer_session:${row.id}`,
    sample_kind: "answer_session",
    source_id: row.id,
    origin,
    severity,
    detected_at: eventAt.toISOString(),
    title: `Answer session: ${truncateText(row.question, 72)}`,
    summary: row.diagnosisCode
      ? `Session status is ${row.status}; diagnosis: ${row.diagnosisCode}.`
      : `Session status is ${row.status}.`,
    related_incident_ref: null,
    related_deployment_record_id: relatedDeploymentRecordId,
    regression_class: null,
    next_replay_ref: {
      method: "GET",
      path: `/api/v1/ops/replays/answer-sessions/${row.id}`
    }
  };

  return {
    sample,
    detectedAtMs: eventAt.getTime(),
    shapeKey: `answer_session:${row.status}:${row.diagnosisCode ?? "none"}`
  };
}

function buildDocumentPipelineCandidate(
  row: DocumentSampleRow,
  origin: OpsDiagnosticOrigin,
  relatedDeploymentRecordId: string | null,
  severityOverride?: IncidentSeverity,
  incidentRefOverride?: string | null
): DiagnosticSampleCandidate {
  const eventAt = toDate(row.eventAt);
  const severity = severityOverride ?? getDocumentSeverity(row.indexStatus, row.citationReady);
  const sample: OpsDiagnosticSample = {
    sample_id: `document:${row.id}`,
    sample_kind: "document_pipeline",
    source_id: row.id,
    origin,
    severity,
    detected_at: eventAt.toISOString(),
    title: row.title,
    summary: getDocumentSampleSummary(row),
    related_incident_ref: incidentRefOverride ?? row.lastIncidentRef,
    related_deployment_record_id: relatedDeploymentRecordId,
    regression_class: null,
    next_replay_ref: {
      method: "GET",
      path: `/api/v1/ops/replays/documents/${row.id}`
    }
  };

  return {
    sample,
    detectedAtMs: eventAt.getTime(),
    shapeKey: `document_pipeline:${row.indexStatus}:${row.diagnosisCode ?? "none"}:${row.citationReady ? "citation_ready" : "citation_unready"}`
  };
}

function getDocumentSampleSummary(row: DocumentSampleRow) {
  const citationSummary = row.citationReady ? "citation ready" : "citation not ready";

  if (row.diagnosisCode) {
    return `Document index status is ${row.indexStatus}; ${citationSummary}; diagnosis: ${row.diagnosisCode}.`;
  }

  return `Document index status is ${row.indexStatus}; ${citationSummary}.`;
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

function getDocumentSeverity(
  indexStatus: DocumentDetail["index_status"] | IndexStatus,
  citationReady = true
): OpsDiagnosticSample["severity"] {
  if (indexStatus === "failed") {
    return "high";
  }

  if (!citationReady || indexStatus === "stale" || indexStatus === "not_indexed") {
    return "medium";
  }

  return "low";
}

function sortCandidates(candidates: DiagnosticSampleCandidate[]) {
  return [...candidates].sort((left, right) => {
    return right.detectedAtMs - left.detectedAtMs || left.sample.sample_id.localeCompare(right.sample.sample_id);
  });
}

function getAnswerEventAtExpression(): SQL<Date> {
  return sql<Date>`coalesce(${answerSessions.finishedAt}, ${answerSessions.updatedAt}, ${answerSessions.createdAt})`;
}

function getDocumentEventAtExpression(): SQL<Date> {
  return sql<Date>`coalesce(${documents.indexedAt}, ${documents.updatedAt}, ${documents.importedAt})`;
}

function resolveIncidentSource(jobType: string): IncidentSource {
  switch (jobType) {
    case "run_ocr":
      return "ocr";
    case "fetch_link":
      return "fetch";
    case "rebuild_search_projection":
    case "refresh_search_projection":
      return "projection";
    default:
      return "parse";
  }
}

function getIncidentSeverity(code: string | null, source: IncidentSource): IncidentSeverity {
  if (source === "ci" || source === "projection") {
    return "medium";
  }

  if (
    code === "queue_backlog" ||
    code === "object_missing_on_complete" ||
    code === "pdf_parse_timeout" ||
    code === "pdf_parse_runtime_error" ||
    code === "ocr_timeout" ||
    code === "ocr_runtime_error" ||
    code === "link_fetch_timeout" ||
    code === "link_fetch_blocked"
  ) {
    return "high";
  }

  return "medium";
}

function getIncidentStatus(jobStatus: string): IncidentStatus {
  return jobStatus === "dead" ? "tracked" : "open";
}

function buildIncidentRef(prefix: "JOB" | "UPL", id: string) {
  return `${prefix}-${id.slice(0, 8).toUpperCase()}`;
}

function truncateText(value: string, maxLength: number) {
  if (value.length <= maxLength) {
    return value;
  }

  return `${value.slice(0, maxLength - 3)}...`;
}

function toDate(value: Date | string) {
  return value instanceof Date ? value : new Date(value);
}
