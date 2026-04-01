import {
  completeUpload as completeUploadRequest,
  createTag as createTagRequest,
  createTextDocument as createTextDocumentRequest,
  fetchHealth as fetchHealthRequest,
  getDocument as getDocumentRequest,
  getJob as getJobRequest,
  initiateUpload as initiateUploadRequest,
  listDocuments as listDocumentsRequest,
  listTags as listTagsRequest,
  retryDocument as retryDocumentRequest,
  updateDocumentTags as updateDocumentTagsRequest
} from "@xrag/api-client";
import type {
  CreateTagRequest,
  CreateTextDocumentRequest,
  DocumentDetail,
  DocumentListResponse,
  HealthResponse,
  JobStatusResponse,
  ListDocumentsQuery,
  ListTagsQuery,
  RetryDocumentResponse,
  TagItem,
  TagListResponse,
  UpdateDocumentTagsRequest,
  UploadCompleteRequest,
  UploadCompleteResponse,
  UploadInitiateRequest,
  UploadInitiateResponse
} from "@xrag/shared-types";

export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";

export function fetchHealth(): Promise<HealthResponse> {
  return fetchHealthRequest(API_BASE_URL);
}

export function listDocuments(query: ListDocumentsQuery = {}): Promise<DocumentListResponse> {
  return listDocumentsRequest(API_BASE_URL, query);
}

export function createTextDocument(body: CreateTextDocumentRequest): Promise<{ id: string; parse_status: "pending" | "processing" | "success" | "failed" }> {
  return createTextDocumentRequest(body, API_BASE_URL);
}

export function getDocument(documentId: string): Promise<DocumentDetail> {
  return getDocumentRequest(documentId, API_BASE_URL);
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

export function initiateUpload(body: UploadInitiateRequest): Promise<UploadInitiateResponse> {
  return initiateUploadRequest(body, API_BASE_URL);
}

export function completeUpload(uploadId: string, body: UploadCompleteRequest): Promise<UploadCompleteResponse> {
  return completeUploadRequest(uploadId, body, API_BASE_URL);
}
