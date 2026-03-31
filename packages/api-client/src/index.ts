import type {
  DocumentDetail,
  DocumentListResponse,
  HealthResponse,
  JobStatusResponse,
  TagListResponse
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

export async function getDocument(documentId: string, baseUrl = "http://localhost:3001") {
  return requestJson<DocumentDetail>(`/api/v1/documents/${documentId}`, undefined, baseUrl);
}

export async function listTags(baseUrl = "http://localhost:3001") {
  return requestJson<TagListResponse>("/api/v1/tags", undefined, baseUrl);
}

export async function getJob(jobId: string, baseUrl = "http://localhost:3001") {
  return requestJson<JobStatusResponse>(`/api/v1/jobs/${jobId}`, undefined, baseUrl);
}
