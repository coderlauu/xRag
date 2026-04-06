import type {
  CreateTagRequest,
  CreateTextDocumentRequest,
  CreateTextDocumentResponse,
  DocumentDetail,
  DocumentListResponse,
  HealthResponse,
  JobStatusResponse,
  LatestDeploymentResponse,
  ListDocumentsQuery,
  ListTagsQuery,
  OpsHealthSummaryResponse,
  OpsIncidentListResponse,
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

export async function getDocument(documentId: string, baseUrl = "http://localhost:3001") {
  return requestJson<DocumentDetail>(`/api/v1/documents/${documentId}`, undefined, baseUrl);
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
