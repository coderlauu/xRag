export type ParseStatus = "pending" | "processing" | "success" | "failed";
export type SourceType = "text" | "file" | "link";
export type SourceOrigin = "manual_input" | "upload" | "link";
export type JobStatus = "queued" | "running" | "succeeded" | "failed" | "dead";
export type TagStatus = "active" | "archived";
export type UploadStatus = "initiated" | "uploaded" | "completed" | "expired";
export type DocumentJobType = "parse_document" | "reparse_document" | "refresh_search_projection";

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
  imported_at: string;
}

export interface DocumentDetail extends DocumentSummary {
  content_raw: string | null;
  content_clean: string | null;
  source_url: string | null;
  mime_type: string | null;
  parse_error_message: string | null;
  created_at: string;
}

export interface DocumentListResponse {
  items: DocumentSummary[];
  page: number;
  page_size: number;
  total: number;
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
}

export interface UploadInitiateRequest {
  file_name: string;
  mime_type: string;
  file_size: number;
}

export interface UploadInitiateResponse {
  upload_id: string;
  object_key: string;
  upload_method: "presigned_put";
  upload_url: string;
  headers: Record<string, string>;
  expires_in: number;
}

export interface UploadCompleteRequest {
  title: string;
  tags: string[];
  checksum_sha256: string;
}

export interface UploadCompleteResponse {
  document_id: string;
  job_id: string;
  parse_status: ParseStatus;
}

export interface TagItem {
  id: string;
  name: string;
  status: TagStatus;
}

export interface TagListResponse {
  items: TagItem[];
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
}
