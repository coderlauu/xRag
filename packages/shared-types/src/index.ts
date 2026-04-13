export type ParseStatus = "pending" | "processing" | "success" | "failed";
export type SourceType = "text" | "file" | "pdf" | "link";
export type SourceOrigin = "manual_input" | "upload" | "link";
export type JobStatus = "queued" | "running" | "succeeded" | "failed" | "dead";
export type TagStatus = "active" | "archived";
export type UploadMode = "single" | "multipart";
export type DocumentUploadStatus = "draft" | "initiated" | "uploading" | "verifying" | "uploaded" | "failed";
export type UploadSessionStatus = "initiated" | "uploading" | "verifying" | "uploaded" | "failed" | "expired";
export type UploadPartStatus = "initiated" | "uploaded" | "failed";
export type OcrStatus = "not_required" | "queued" | "processing" | "success" | "failed";
export type IndexStatus = "not_indexed" | "queued" | "chunking" | "embedding" | "ready" | "failed" | "stale";
export type AnswerScopeMode = "global" | "search_result" | "document";
export type RetrievalMode = "hybrid";
export type AnswerSessionStatus =
  | "idle"
  | "retrieving"
  | "synthesizing"
  | "answered"
  | "needs_scope"
  | "refused"
  | "failed";
export type RetrievalExclusionReason =
  | "deduplicated"
  | "rerank_cutoff"
  | "answer_budget"
  | "low_support"
  | "citation_unready";
export type AnswerClaimFreshnessBadge = "ready" | "stale_risk" | "unknown";
export type DocumentJobType =
  | "parse_document"
  | "reparse_document"
  | "refresh_search_projection"
  | "run_ocr"
  | "fetch_link"
  | "rebuild_search_projection"
  | "chunk_document"
  | "embed_document";
export type DocumentProcessingJobName =
  | "parse_document"
  | "reparse_document"
  | "refresh_search_projection"
  | "run_ocr"
  | "fetch_link"
  | "rebuild_search_projection";
export type DocumentIndexingJobName = "chunk_document" | "embed_document";
export type AnswerOrchestrationJobName = "answer_session";
export type DiagnosisCode =
  | "storage_presign_failed"
  | "multipart_part_failed"
  | "upload_complete_invalid_parts"
  | "object_missing_on_complete"
  | "pdf_parse_runtime_error"
  | "pdf_parse_unsupported"
  | "pdf_parse_timeout"
  | "pdf_parse_empty_text"
  | "queue_backlog"
  | "ocr_runtime_error"
  | "ocr_timeout"
  | "ocr_no_text_detected"
  | "link_fetch_timeout"
  | "link_fetch_blocked"
  | "link_extract_empty"
  | "link_invalid_url"
  | "search_projection_stale"
  | "index_chunk_failed"
  | "index_embedding_failed"
  | "retrieval_no_hits"
  | "retrieval_scope_empty"
  | "answer_insufficient_evidence"
  | "citation_missing"
  | "provider_timeout";
export type OpsServiceStatus = "healthy" | "warning" | "critical";
export type IncidentSource = "upload" | "parse" | "ocr" | "fetch" | "projection" | "deploy" | "ci";
export type IncidentSeverity = "low" | "medium" | "high";
export type IncidentStatus = "open" | "tracked" | "resolved";
export type DeploymentSmokeStatus = "passed" | "failed" | "unknown";
export type ProcessingEventStage = "upload" | "parse" | "ocr" | "fetch" | "projection" | "ops" | "index";

export const DOCUMENT_PROCESSING_QUEUE_NAME = "document-processing" as const;
export const DOCUMENT_INDEXING_QUEUE_NAME = "document-indexing" as const;
export const ANSWER_ORCHESTRATION_QUEUE_NAME = "answer-orchestration" as const;

export const DOCUMENT_PROCESSING_JOB_NAMES = {
  parseDocument: "parse_document",
  reparseDocument: "reparse_document",
  refreshSearchProjection: "refresh_search_projection",
  runOcr: "run_ocr",
  fetchLink: "fetch_link",
  rebuildSearchProjection: "rebuild_search_projection"
} as const;

export const DOCUMENT_INDEXING_JOB_NAMES = {
  chunkDocument: "chunk_document",
  embedDocument: "embed_document"
} as const;

export const ANSWER_ORCHESTRATION_JOB_NAMES = {
  answerSession: "answer_session"
} as const;

export interface DocumentIndexingJobData {
  documentId: string;
  jobId: string;
}

export interface AnswerOrchestrationJobData {
  sessionId: string;
}

export interface HealthResponse {
  status: string;
}

export interface ReadinessResponse extends HealthResponse {
  checks: Record<string, string>;
}

export interface DocumentSummary {
  id: string;
  title: string;
  content_preview: string;
  tags: string[];
  source_type: SourceType;
  source_origin: SourceOrigin;
  source_url: string | null;
  file_name: string | null;
  parse_status: ParseStatus;
  index_status: IndexStatus;
  indexed_at: string | null;
  citation_ready: boolean;
  ocr_status: OcrStatus | null;
  upload_status: DocumentUploadStatus | null;
  diagnosis_code: DiagnosisCode | null;
  diagnosis_summary: string | null;
  match_explanation: string | null;
  ranking_hint: string | null;
  matched_fields: string[] | null;
  latest_job_status: JobStatus | null;
  page_count: number | null;
  parser_name: string | null;
  imported_at: string;
}

export interface DocumentUploadInfo {
  id: string;
  upload_mode: UploadMode;
  status: UploadSessionStatus;
  part_count: number | null;
  uploaded_part_count: number;
  verified_at: string | null;
}

export interface DocumentLatestJobInfo {
  id: string;
  status: JobStatus;
  diagnosis_code: DiagnosisCode | null;
  finished_at: string | null;
}

export interface DocumentDetail extends DocumentSummary {
  content_raw: string | null;
  content_clean: string | null;
  mime_type: string | null;
  parse_error_message: string | null;
  ocr_engine: string | null;
  ocr_language: string | null;
  upload: DocumentUploadInfo | null;
  latest_job: DocumentLatestJobInfo | null;
  last_incident_ref: string | null;
  index_version: string | null;
  page_count: number | null;
  parser_name: string | null;
  parser_version: string | null;
  created_at: string;
}

export interface DocumentListResponse {
  items: DocumentSummary[];
  page: number;
  page_size: number;
  total: number;
}

export interface ListDocumentsQuery {
  q?: string;
  source_type?: SourceType;
  ocr_status?: OcrStatus;
  parse_status?: string;
  index_status?: IndexStatus;
  upload_status?: string;
  diagnosis_code?: string;
  tags?: string;
  date_from?: string;
  date_to?: string;
  page?: number;
  page_size?: number;
}

export interface UpdateDocumentTagsRequest {
  tags: string[];
}

export interface CreateTextDocumentRequest {
  title: string;
  content: string;
  tags: string[];
}

export interface CreateTextDocumentResponse {
  id: string;
  parse_status: ParseStatus;
}

export interface CreateLinkDocumentRequest {
  title?: string;
  source_url: string;
  tags: string[];
}

export interface CreateLinkDocumentResponse {
  id: string;
  parse_status: ParseStatus;
  diagnosis_code: DiagnosisCode | null;
}

export interface RetryDocumentResponse {
  document_id: string;
  job_id: string;
  parse_status: ParseStatus;
  diagnosis_code: DiagnosisCode | null;
}

export interface DocumentEvidenceItem {
  chunk_id: string;
  chunk_index: number;
  section_label: string | null;
  page_ref: string | null;
  quote_text: string;
  locator: Record<string, unknown> | null;
}

export interface DocumentEvidenceResponse {
  document_id: string;
  index_status: IndexStatus;
  citation_ready: boolean;
  items: DocumentEvidenceItem[];
}

export interface ReindexDocumentResponse {
  document_id: string;
  job_id: string;
  index_status: IndexStatus;
  diagnosis_code: DiagnosisCode | null;
}

export interface UploadInitiateRequest {
  file_name: string;
  mime_type: string;
  file_size: number;
  checksum_sha256?: string;
}

export interface UploadInitiateResponse {
  upload_id: string;
  upload_mode: UploadMode;
  object_key: string;
  status: UploadSessionStatus;
  upload_method?: "presigned_put";
  upload_url?: string;
  headers?: Record<string, string>;
  part_size_bytes?: number;
  part_count?: number;
  provider_upload_id?: string | null;
  expires_in: number;
}

export interface UploadPartUrlRequest {
  part_numbers: number[];
}

export interface UploadPartUrlItem {
  part_number: number;
  upload_url: string;
  headers: Record<string, string>;
}

export interface UploadPartUrlResponse {
  upload_id: string;
  parts: UploadPartUrlItem[];
}

export interface UploadPartCompleteRequest {
  etag: string;
  size_bytes: number;
}

export interface UploadPartCompleteResponse {
  upload_id: string;
  part_number: number;
  status: UploadPartStatus;
  uploaded_part_count: number;
}

export interface CompletedUploadPart {
  part_number: number;
  etag: string;
}

export interface UploadCompleteRequest {
  title: string;
  tags: string[];
  checksum_sha256: string;
  parts?: CompletedUploadPart[];
}

export interface UploadCompleteResponse {
  upload_id: string;
  document_id: string;
  job_id: string;
  upload_status: DocumentUploadStatus;
  parse_status: ParseStatus;
  diagnosis_code: DiagnosisCode | null;
}

export interface TagItem {
  id: string;
  name: string;
  status: TagStatus;
}

export interface TagListResponse {
  items: TagItem[];
}

export interface ListTagsQuery {
  q?: string;
  status?: TagStatus;
}

export interface CreateTagRequest {
  name: string;
}

export interface JobStatusResponse {
  id: string;
  document_id: string;
  job_type: DocumentJobType;
  status: JobStatus;
  attempt: number;
  error_message: string | null;
  diagnosis_code: DiagnosisCode | null;
  incident_ref: string | null;
  runtime_ms: number | null;
}

export interface DocumentProcessingEventItem {
  event_type: string;
  stage: ProcessingEventStage;
  status: ParseStatus;
  diagnosis_code: DiagnosisCode | null;
  summary: string;
  created_at: string;
}

export interface DocumentTimelineResponse {
  document_id: string;
  items: DocumentProcessingEventItem[];
}

export interface ScopeFilterSet {
  tags?: string[];
  source_types?: SourceType[];
  date_from?: string;
  date_to?: string;
}

export interface GlobalAnswerScope {
  mode: "global";
  payload: {
    filters?: ScopeFilterSet | null;
  } | null;
}

export interface DocumentAnswerScope {
  mode: "document";
  payload: {
    document_id: string;
  };
}

export interface SearchResultAnswerScope {
  mode: "search_result";
  payload: {
    document_ids: string[];
    truncated: boolean;
    query?: string | null;
    filters?: ScopeFilterSet | null;
  };
}

export type AnswerScope = GlobalAnswerScope | DocumentAnswerScope | SearchResultAnswerScope;

export interface CreateAnswerRequest {
  question: string;
  scope: AnswerScope;
  continued_from_session_id?: string;
}

export interface CreateAnswerResponse {
  session_id: string;
  status: AnswerSessionStatus;
}

export interface AnswerCitation {
  document_id: string;
  chunk_id: string;
  quote_text: string;
  locator: Record<string, unknown> | null;
}

export interface AnswerEvidenceGroup {
  claim_slot: string;
  claim_text: string;
  freshness_badge: AnswerClaimFreshnessBadge;
  citations: AnswerCitation[];
}

export interface AnswerSessionResponse {
  session_id: string;
  question: string;
  scope: AnswerScope;
  scope_summary: string;
  continued_from_session_id: string | null;
  status: AnswerSessionStatus;
  answer_summary: string | null;
  refusal_reason: string | null;
  diagnosis_code: DiagnosisCode | null;
  retrieval_mode: RetrievalMode;
  citations: AnswerCitation[];
  evidence_groups: AnswerEvidenceGroup[];
  latency_ms: number | null;
  total_cost_usd: string | null;
  updated_at: string;
}

export interface AnswerRetrievalTraceSummary {
  query_normalized: string;
  eligible_document_count: number;
  lexical_hit_count: number;
  semantic_hit_count: number;
  merged_hit_count: number;
  rerank_strategy: RetrievalMode;
  latency_ms: number | null;
}

export interface AnswerRetrievalTraceItem {
  document_id: string;
  chunk_id: string | null;
  rank: number;
  lexical_score: number | null;
  semantic_score: number | null;
  final_score: number | null;
  used_in_answer: boolean;
  exclusion_reason: RetrievalExclusionReason | null;
}

export interface AnswerRetrievalTraceResponse {
  session_id: string;
  summary: AnswerRetrievalTraceSummary | null;
  items: AnswerRetrievalTraceItem[];
}

export interface AnswerHistoryListItem {
  session_id: string;
  question: string;
  status: AnswerSessionStatus;
  scope: AnswerScope;
  scope_summary: string;
  continued_from_session_id: string | null;
  updated_at: string;
}

export interface ListAnswerSessionsQuery {
  page?: number;
  page_size?: number;
}

export interface ListAnswerSessionsResponse {
  items: AnswerHistoryListItem[];
  page: number;
  page_size: number;
  total: number;
}

export interface OpsHealthService {
  name: string;
  status: OpsServiceStatus;
  detail: string;
}

export interface OpsHealthSummaryResponse {
  services: OpsHealthService[];
  generated_at: string;
}

export interface OpsIncidentSummary {
  incident_ref: string;
  source: IncidentSource;
  severity: IncidentSeverity;
  status: IncidentStatus;
  title: string;
  summary: string;
  external_url: string | null;
}

export interface OpsIncidentListResponse {
  items: OpsIncidentSummary[];
}

export interface OpsAnswerSummaryResponse {
  embedding_backlog: number;
  ready_document_count: number;
  stale_document_count: number;
  failed_document_count: number;
  answer_latency_p95: number | null;
  citation_coverage: number | null;
  refusal_rate: number | null;
  avg_token_cost_usd: string | null;
}

export interface LatestDeploymentResponse {
  current_image_tag: string | null;
  previous_stable_image_tag: string | null;
  last_smoke_status: DeploymentSmokeStatus;
  last_smoke_at: string | null;
}
