export type ParseStatus = "pending" | "processing" | "success" | "failed";
export type SourceType = "text" | "file" | "link";
export type SourceOrigin = "manual_input" | "upload" | "link";
export type JobStatus = "queued" | "running" | "succeeded" | "failed" | "dead";
export type TagStatus = "active" | "archived";
export type UploadMode = "single" | "multipart";
export type DocumentUploadStatus = "draft" | "initiated" | "uploading" | "verifying" | "uploaded" | "failed";
export type UploadSessionStatus = "initiated" | "uploading" | "verifying" | "uploaded" | "failed" | "expired";
export type UploadPartStatus = "initiated" | "uploaded" | "failed";
export type DocumentJobType = "parse_document" | "reparse_document" | "refresh_search_projection";
export type DocumentProcessingJobName =
  | "parse-document"
  | "reparse-document"
  | "refresh-search-projection";
export type DiagnosisCode =
  | "storage_presign_failed"
  | "multipart_part_failed"
  | "upload_complete_invalid_parts"
  | "object_missing_on_complete"
  | "pdf_parse_unsupported"
  | "pdf_parse_timeout"
  | "pdf_parse_empty_text"
  | "queue_backlog";
export type OpsServiceStatus = "healthy" | "warning" | "critical";
export type IncidentSource = "upload" | "parse" | "deploy" | "ci";
export type IncidentSeverity = "low" | "medium" | "high";
export type IncidentStatus = "open" | "tracked" | "resolved";
export type DeploymentSmokeStatus = "passed" | "failed" | "unknown";

export const DOCUMENT_PROCESSING_QUEUE_NAME = "document-processing" as const;

export const DOCUMENT_PROCESSING_JOB_NAMES = {
  parseDocument: "parse-document",
  reparseDocument: "reparse-document",
  refreshSearchProjection: "refresh-search-projection"
} as const;

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
  file_name: string | null;
  parse_status: ParseStatus;
  upload_status: DocumentUploadStatus | null;
  diagnosis_code: DiagnosisCode | null;
  diagnosis_summary: string | null;
  latest_job_status: JobStatus | null;
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
  source_url: string | null;
  mime_type: string | null;
  parse_error_message: string | null;
  upload: DocumentUploadInfo | null;
  latest_job: DocumentLatestJobInfo | null;
  last_incident_ref: string | null;
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
  parse_status?: string;
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

export interface RetryDocumentResponse {
  document_id: string;
  job_id: string;
  parse_status: ParseStatus;
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

export interface LatestDeploymentResponse {
  current_image_tag: string | null;
  previous_stable_image_tag: string | null;
  last_smoke_status: DeploymentSmokeStatus;
  last_smoke_at: string | null;
}
