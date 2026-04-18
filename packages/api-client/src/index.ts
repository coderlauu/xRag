import type {
  AnswerRetrievalTraceResponse,
  AnswerSessionResponse,
  CreateAnswerRequest,
  CreateAnswerResponse,
  CreateTagRequest,
  CreateLinkDocumentRequest,
  CreateLinkDocumentResponse,
  CreateTextDocumentRequest,
  CreateTextDocumentResponse,
  DocumentDetail,
  DocumentEvidenceResponse,
  DocumentListResponse,
  DocumentTimelineResponse,
  HealthResponse,
  JobStatusResponse,
  LatestDeploymentResponse,
  ListAnswerSessionsQuery,
  ListAnswerSessionsResponse,
  ListDocumentsQuery,
  ListTagsQuery,
  OpsAnswerSummaryResponse,
  OpsAnswerSessionReplayResponse,
  OpsDeploymentCompareQuery,
  OpsDeploymentCompareResponse,
  OpsDiagnosticSampleListQuery,
  OpsDiagnosticSampleListResponse,
  OpsDocumentReplayResponse,
  OpsHealthSummaryResponse,
  OpsIncidentListResponse,
  OpsOverviewResponse,
  OpsRecoveryActionAuditResponse,
  OpsRecoveryActionCreateRequest,
  OpsRecoveryActionPreviewRequest,
  OpsRecoveryActionPreviewResponse,
  OpsRecoveryActionResponse,
  OpsRecoveryCandidateListQuery,
  OpsRecoveryCandidateListResponse,
  OpsRollbackPlanQuery,
  OpsRollbackPlanResponse,
  OpsTrendsQuery,
  OpsTrendsResponse,
  ReindexDocumentResponse,
  RetryDocumentResponse,
  TagItem,
  TagListResponse,
  UpdateDocumentTagsRequest,
  UploadCompleteRequest,
  UploadCompleteResponse,
  UploadInitiateRequest,
  UploadInitiateResponse,
  UploadPartCompleteRequest,
  UploadPartCompleteResponse,
  UploadPartUrlRequest,
  UploadPartUrlResponse
} from "@xrag/shared-types";

async function requestJson<T>(path: string, init?: RequestInit, baseUrl = "http://localhost:3001"): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, init);
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${path}`);
  }

  return response.json() as Promise<T>;
}

function buildSearchParams(query: object) {
  const params = new URLSearchParams();

  for (const [key, value] of Object.entries(query as Record<string, string | number | undefined>)) {
    if (value !== undefined && value !== "") {
      params.set(key, String(value));
    }
  }

  const encoded = params.toString();
  return encoded ? `?${encoded}` : "";
}

export async function fetchHealth(baseUrl = "http://localhost:3001"): Promise<HealthResponse> {
  return requestJson<HealthResponse>("/api/v1/health", undefined, baseUrl);
}

export async function listDocuments(baseUrl = "http://localhost:3001", query: ListDocumentsQuery = {}) {
  const search = buildSearchParams(query);
  return requestJson<DocumentListResponse>(`/api/v1/documents${search}`, undefined, baseUrl);
}

export async function createTextDocument(
  body: CreateTextDocumentRequest,
  baseUrl = "http://localhost:3001"
) {
  return requestJson<CreateTextDocumentResponse>(
    "/api/v1/documents/text",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    },
    baseUrl
  );
}

export async function createLinkDocument(body: CreateLinkDocumentRequest, baseUrl = "http://localhost:3001") {
  return requestJson<CreateLinkDocumentResponse>(
    "/api/v1/documents/link",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    },
    baseUrl
  );
}

export async function getDocument(documentId: string, baseUrl = "http://localhost:3001") {
  return requestJson<DocumentDetail>(`/api/v1/documents/${documentId}`, undefined, baseUrl);
}

export async function getDocumentEvidence(documentId: string, baseUrl = "http://localhost:3001") {
  return requestJson<DocumentEvidenceResponse>(`/api/v1/documents/${documentId}/evidence`, undefined, baseUrl);
}

export async function getDocumentTimeline(documentId: string, baseUrl = "http://localhost:3001") {
  return requestJson<DocumentTimelineResponse>(`/api/v1/documents/${documentId}/timeline`, undefined, baseUrl);
}

export async function updateDocumentTags(
  documentId: string,
  body: UpdateDocumentTagsRequest,
  baseUrl = "http://localhost:3001"
) {
  return requestJson<DocumentDetail>(
    `/api/v1/documents/${documentId}/tags`,
    {
      method: "PATCH",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    },
    baseUrl
  );
}

export async function retryDocument(documentId: string, baseUrl = "http://localhost:3001") {
  return requestJson<RetryDocumentResponse>(
    `/api/v1/documents/${documentId}/retry`,
    {
      method: "POST"
    },
    baseUrl
  );
}

export async function reindexDocument(documentId: string, baseUrl = "http://localhost:3001") {
  return requestJson<ReindexDocumentResponse>(
    `/api/v1/documents/${documentId}/reindex`,
    {
      method: "POST"
    },
    baseUrl
  );
}

export async function listTags(baseUrl = "http://localhost:3001", query: ListTagsQuery = {}) {
  const search = buildSearchParams(query);
  return requestJson<TagListResponse>(`/api/v1/tags${search}`, undefined, baseUrl);
}

export async function createTag(body: CreateTagRequest, baseUrl = "http://localhost:3001") {
  return requestJson<TagItem>(
    "/api/v1/tags",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    },
    baseUrl
  );
}

export async function getJob(jobId: string, baseUrl = "http://localhost:3001") {
  return requestJson<JobStatusResponse>(`/api/v1/jobs/${jobId}`, undefined, baseUrl);
}

export async function initiateUpload(body: UploadInitiateRequest, baseUrl = "http://localhost:3001") {
  return requestJson<UploadInitiateResponse>(
    "/api/v1/uploads/initiate",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    },
    baseUrl
  );
}

export async function getUploadPartUrls(
  uploadId: string,
  body: UploadPartUrlRequest,
  baseUrl = "http://localhost:3001"
) {
  return requestJson<UploadPartUrlResponse>(
    `/api/v1/uploads/${uploadId}/parts`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    },
    baseUrl
  );
}

export async function completeUploadPart(
  uploadId: string,
  partNumber: number,
  body: UploadPartCompleteRequest,
  baseUrl = "http://localhost:3001"
) {
  return requestJson<UploadPartCompleteResponse>(
    `/api/v1/uploads/${uploadId}/parts/${partNumber}/complete`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    },
    baseUrl
  );
}

export async function completeUpload(
  uploadId: string,
  body: UploadCompleteRequest,
  baseUrl = "http://localhost:3001"
) {
  return requestJson<UploadCompleteResponse>(
    `/api/v1/uploads/${uploadId}/complete`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    },
    baseUrl
  );
}

export async function fetchOpsHealthSummary(baseUrl = "http://localhost:3001") {
  return requestJson<OpsHealthSummaryResponse>("/api/v1/ops/health-summary", undefined, baseUrl);
}

export async function listOpsIncidents(baseUrl = "http://localhost:3001") {
  return requestJson<OpsIncidentListResponse>("/api/v1/ops/incidents", undefined, baseUrl);
}

export async function getLatestDeployment(baseUrl = "http://localhost:3001") {
  return requestJson<LatestDeploymentResponse>("/api/v1/ops/deployments/latest", undefined, baseUrl);
}

export async function fetchOpsAnswerSummary(baseUrl = "http://localhost:3001") {
  return requestJson<OpsAnswerSummaryResponse>("/api/v1/ops/answer-summary", undefined, baseUrl);
}

export async function fetchOpsOverview(baseUrl = "http://localhost:3001") {
  return requestJson<OpsOverviewResponse>("/api/v1/ops/overview", undefined, baseUrl);
}

export async function fetchOpsTrends(baseUrl = "http://localhost:3001", query: OpsTrendsQuery = {}) {
  const search = buildSearchParams(query);
  return requestJson<OpsTrendsResponse>(`/api/v1/ops/trends${search}`, undefined, baseUrl);
}

export async function fetchOpsDiagnosticSamples(
  query: OpsDiagnosticSampleListQuery,
  baseUrl = "http://localhost:3001"
) {
  const search = buildSearchParams(query);
  return requestJson<OpsDiagnosticSampleListResponse>(`/api/v1/ops/samples${search}`, undefined, baseUrl);
}

export async function fetchOpsAnswerSessionReplay(sessionId: string, baseUrl = "http://localhost:3001") {
  return requestJson<OpsAnswerSessionReplayResponse>(
    `/api/v1/ops/replays/answer-sessions/${sessionId}`,
    undefined,
    baseUrl
  );
}

export async function fetchOpsDocumentReplay(documentId: string, baseUrl = "http://localhost:3001") {
  return requestJson<OpsDocumentReplayResponse>(`/api/v1/ops/replays/documents/${documentId}`, undefined, baseUrl);
}

export async function fetchOpsDeploymentCompare(
  query: OpsDeploymentCompareQuery,
  baseUrl = "http://localhost:3001"
) {
  const search = buildSearchParams(query);
  return requestJson<OpsDeploymentCompareResponse>(`/api/v1/ops/deployments/compare${search}`, undefined, baseUrl);
}

export async function fetchOpsRecoveryCandidates(
  query: OpsRecoveryCandidateListQuery = {},
  baseUrl = "http://localhost:3001"
) {
  const search = buildSearchParams(query);
  return requestJson<OpsRecoveryCandidateListResponse>(`/api/v1/ops/recovery/candidates${search}`, undefined, baseUrl);
}

export async function previewOpsRecoveryAction(
  body: OpsRecoveryActionPreviewRequest,
  baseUrl = "http://localhost:3001"
) {
  return requestJson<OpsRecoveryActionPreviewResponse>(
    "/api/v1/ops/recovery/actions/preview",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    },
    baseUrl
  );
}

export async function createOpsRecoveryAction(
  body: OpsRecoveryActionCreateRequest,
  baseUrl = "http://localhost:3001"
) {
  return requestJson<OpsRecoveryActionResponse>(
    "/api/v1/ops/recovery/actions",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    },
    baseUrl
  );
}

export async function getOpsRecoveryAction(actionId: string, baseUrl = "http://localhost:3001") {
  return requestJson<OpsRecoveryActionResponse>(`/api/v1/ops/recovery/actions/${actionId}`, undefined, baseUrl);
}

export async function getOpsRecoveryActionAudit(actionId: string, baseUrl = "http://localhost:3001") {
  return requestJson<OpsRecoveryActionAuditResponse>(
    `/api/v1/ops/recovery/actions/${actionId}/audit`,
    undefined,
    baseUrl
  );
}

export async function fetchOpsRollbackPlan(query: OpsRollbackPlanQuery, baseUrl = "http://localhost:3001") {
  const search = buildSearchParams(query);
  return requestJson<OpsRollbackPlanResponse>(`/api/v1/ops/recovery/rollback-plan${search}`, undefined, baseUrl);
}

export async function createAnswer(body: CreateAnswerRequest, baseUrl = "http://localhost:3001") {
  return requestJson<CreateAnswerResponse>(
    "/api/v1/answers",
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(body)
    },
    baseUrl
  );
}

export async function listAnswers(baseUrl = "http://localhost:3001", query: ListAnswerSessionsQuery = {}) {
  const search = buildSearchParams(query);
  return requestJson<ListAnswerSessionsResponse>(`/api/v1/answers${search}`, undefined, baseUrl);
}

export async function getAnswer(sessionId: string, baseUrl = "http://localhost:3001") {
  return requestJson<AnswerSessionResponse>(`/api/v1/answers/${sessionId}`, undefined, baseUrl);
}

export async function getAnswerRetrieval(sessionId: string, baseUrl = "http://localhost:3001") {
  return requestJson<AnswerRetrievalTraceResponse>(`/api/v1/answers/${sessionId}/retrieval`, undefined, baseUrl);
}
