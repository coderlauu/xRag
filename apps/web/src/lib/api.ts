import {
  createLinkDocument as createLinkDocumentRequest,
  completeUpload as completeUploadRequest,
  completeUploadPart as completeUploadPartRequest,
  createTag as createTagRequest,
  createTextDocument as createTextDocumentRequest,
  fetchHealth as fetchHealthRequest,
  fetchOpsHealthSummary as fetchOpsHealthSummaryRequest,
  getDocument as getDocumentRequest,
  getDocumentTimeline as getDocumentTimelineRequest,
  getUploadPartUrls as getUploadPartUrlsRequest,
  getJob as getJobRequest,
  getLatestDeployment as getLatestDeploymentRequest,
  initiateUpload as initiateUploadRequest,
  listDocuments as listDocumentsRequest,
  listTags as listTagsRequest,
  listOpsIncidents as listOpsIncidentsRequest,
  retryDocument as retryDocumentRequest,
  updateDocumentTags as updateDocumentTagsRequest
} from "@xrag/api-client";
import type {
  CreateTagRequest,
  CreateLinkDocumentRequest,
  CreateLinkDocumentResponse,
  CreateTextDocumentRequest,
  CreateTextDocumentResponse,
  DocumentDetail,
  DocumentListResponse,
  DocumentTimelineResponse,
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
  UploadInitiateResponse
  ,
  UploadPartCompleteRequest,
  UploadPartCompleteResponse,
  UploadPartUrlRequest,
  UploadPartUrlResponse
} from "@xrag/shared-types";

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";

export function fetchHealth(): Promise<HealthResponse> {
  return fetchHealthRequest(API_BASE_URL);
}

export function listDocuments(query: ListDocumentsQuery = {}): Promise<DocumentListResponse> {
  return listDocumentsRequest(API_BASE_URL, query);
}

export function createTextDocument(body: CreateTextDocumentRequest): Promise<CreateTextDocumentResponse> {
  return createTextDocumentRequest(body, API_BASE_URL);
}

export function createLinkDocument(body: CreateLinkDocumentRequest): Promise<CreateLinkDocumentResponse> {
  return createLinkDocumentRequest(body, API_BASE_URL);
}

export function getDocument(documentId: string): Promise<DocumentDetail> {
  return getDocumentRequest(documentId, API_BASE_URL);
}

export function getDocumentTimeline(documentId: string): Promise<DocumentTimelineResponse> {
  return getDocumentTimelineRequest(documentId, API_BASE_URL);
}

export function updateDocumentTags(documentId: string, body: UpdateDocumentTagsRequest): Promise<DocumentDetail> {
  return updateDocumentTagsRequest(documentId, body, API_BASE_URL);
}

export function retryDocument(documentId: string): Promise<RetryDocumentResponse> {
  return retryDocumentRequest(documentId, API_BASE_URL);
}

export function listTags(query: ListTagsQuery = {}): Promise<TagListResponse> {
  return listTagsRequest(API_BASE_URL, query);
}

export function createTag(body: CreateTagRequest): Promise<TagItem> {
  return createTagRequest(body, API_BASE_URL);
}

export function getJob(jobId: string): Promise<JobStatusResponse> {
  return getJobRequest(jobId, API_BASE_URL);
}

export function fetchOpsHealthSummary(): Promise<OpsHealthSummaryResponse> {
  return fetchOpsHealthSummaryRequest(API_BASE_URL);
}

export function listOpsIncidents(): Promise<OpsIncidentListResponse> {
  return listOpsIncidentsRequest(API_BASE_URL);
}

export function getLatestDeployment(): Promise<LatestDeploymentResponse> {
  return getLatestDeploymentRequest(API_BASE_URL);
}

export function initiateUpload(body: UploadInitiateRequest): Promise<UploadInitiateResponse> {
  return initiateUploadRequest(body, API_BASE_URL);
}

export function getUploadPartUrls(
  uploadId: string,
  body: UploadPartUrlRequest
): Promise<UploadPartUrlResponse> {
  return getUploadPartUrlsRequest(uploadId, body, API_BASE_URL);
}

export function completeUploadPart(
  uploadId: string,
  partNumber: number,
  body: UploadPartCompleteRequest
): Promise<UploadPartCompleteResponse> {
  return completeUploadPartRequest(uploadId, partNumber, body, API_BASE_URL);
}

export function completeUpload(uploadId: string, body: UploadCompleteRequest): Promise<UploadCompleteResponse> {
  return completeUploadRequest(uploadId, body, API_BASE_URL);
}
