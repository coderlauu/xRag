import type {
  CreateTagRequest,
  CreateTextDocumentRequest,
  CreateTextDocumentResponse,
  DocumentDetail,
  DocumentListResponse,
  HealthResponse,
  JobStatusResponse,
  RetryDocumentResponse,
  TagItem,
  TagListResponse,
  UpdateDocumentTagsRequest,
  UploadCompleteRequest,
  UploadCompleteResponse,
  UploadInitiateRequest,
  UploadInitiateResponse
} from "@xrag/shared-types";

async function requestJson<T>(path: string, init?: RequestInit, baseUrl = "http://localhost:3001"): Promise<T> {
  const response = await fetch(`${baseUrl}${path}`, init);
  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${path}`);
  }

  return response.json() as Promise<T>;
}

export async function fetchHealth(baseUrl = "http://localhost:3001"): Promise<HealthResponse> {
  return requestJson<HealthResponse>("/api/v1/health", undefined, baseUrl);
}

export async function listDocuments(baseUrl = "http://localhost:3001", query?: string) {
  const search = query ? `?q=${encodeURIComponent(query)}` : "";
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

export async function listTags(baseUrl = "http://localhost:3001") {
  return requestJson<TagListResponse>("/api/v1/tags", undefined, baseUrl);
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
