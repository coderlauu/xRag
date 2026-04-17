import {
  createAnswer as createAnswerRequest,
  createLinkDocument as createLinkDocumentRequest,
  listAnswers as listAnswersRequest,
  completeUpload as completeUploadRequest,
  completeUploadPart as completeUploadPartRequest,
  createTag as createTagRequest,
  createTextDocument as createTextDocumentRequest,
  fetchHealth as fetchHealthRequest,
  fetchOpsAnswerSummary as fetchOpsAnswerSummaryRequest,
  fetchOpsAnswerSessionReplay as fetchOpsAnswerSessionReplayRequest,
  fetchOpsDeploymentCompare as fetchOpsDeploymentCompareRequest,
  fetchOpsDiagnosticSamples as fetchOpsDiagnosticSamplesRequest,
  fetchOpsDocumentReplay as fetchOpsDocumentReplayRequest,
  fetchOpsHealthSummary as fetchOpsHealthSummaryRequest,
  fetchOpsOverview as fetchOpsOverviewRequest,
  fetchOpsTrends as fetchOpsTrendsRequest,
  getAnswer as getAnswerRequest,
  getAnswerRetrieval as getAnswerRetrievalRequest,
  getDocument as getDocumentRequest,
  getDocumentEvidence as getDocumentEvidenceRequest,
  getDocumentTimeline as getDocumentTimelineRequest,
  getUploadPartUrls as getUploadPartUrlsRequest,
  getJob as getJobRequest,
  getLatestDeployment as getLatestDeploymentRequest,
  initiateUpload as initiateUploadRequest,
  listDocuments as listDocumentsRequest,
  listTags as listTagsRequest,
  listOpsIncidents as listOpsIncidentsRequest,
  reindexDocument as reindexDocumentRequest,
  retryDocument as retryDocumentRequest,
  updateDocumentTags as updateDocumentTagsRequest
} from "@xrag/api-client";
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

export function getDocumentEvidence(documentId: string): Promise<DocumentEvidenceResponse> {
  return getDocumentEvidenceRequest(documentId, API_BASE_URL);
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

export function reindexDocument(documentId: string): Promise<ReindexDocumentResponse> {
  return reindexDocumentRequest(documentId, API_BASE_URL);
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

export function fetchOpsAnswerSummary(): Promise<OpsAnswerSummaryResponse> {
  return fetchOpsAnswerSummaryRequest(API_BASE_URL);
}

export function fetchOpsOverview(): Promise<OpsOverviewResponse> {
  return fetchOpsOverviewRequest(API_BASE_URL);
}

export function fetchOpsTrends(query: OpsTrendsQuery = {}): Promise<OpsTrendsResponse> {
  return fetchOpsTrendsRequest(API_BASE_URL, query);
}

export function fetchOpsDiagnosticSamples(
  query: OpsDiagnosticSampleListQuery
): Promise<OpsDiagnosticSampleListResponse> {
  return fetchOpsDiagnosticSamplesRequest(query, API_BASE_URL);
}

export function fetchOpsAnswerSessionReplay(sessionId: string): Promise<OpsAnswerSessionReplayResponse> {
  return fetchOpsAnswerSessionReplayRequest(sessionId, API_BASE_URL);
}

export function fetchOpsDocumentReplay(documentId: string): Promise<OpsDocumentReplayResponse> {
  return fetchOpsDocumentReplayRequest(documentId, API_BASE_URL);
}

export function fetchOpsDeploymentCompare(query: OpsDeploymentCompareQuery): Promise<OpsDeploymentCompareResponse> {
  return fetchOpsDeploymentCompareRequest(query, API_BASE_URL);
}

export function getLatestDeployment(): Promise<LatestDeploymentResponse> {
  return getLatestDeploymentRequest(API_BASE_URL);
}

export function createAnswer(body: CreateAnswerRequest): Promise<CreateAnswerResponse> {
  return createAnswerRequest(body, API_BASE_URL);
}

export function listAnswers(query: ListAnswerSessionsQuery = {}): Promise<ListAnswerSessionsResponse> {
  return listAnswersRequest(API_BASE_URL, query);
}

export function getAnswer(sessionId: string): Promise<AnswerSessionResponse> {
  return getAnswerRequest(sessionId, API_BASE_URL);
}

export function getAnswerRetrieval(sessionId: string): Promise<AnswerRetrievalTraceResponse> {
  return getAnswerRetrievalRequest(sessionId, API_BASE_URL);
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
