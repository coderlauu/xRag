import { ApiProperty, ApiPropertyOptional } from "@nestjs/swagger";
import type {
  CreateLinkDocumentRequest,
  CreateLinkDocumentResponse,
  CreateTextDocumentRequest,
  CreateTextDocumentResponse,
  DiagnosisCode,
  DocumentDetail,
  DocumentEvidenceItem,
  DocumentEvidenceResponse,
  DocumentLatestJobInfo,
  DocumentListResponse,
  DocumentProcessingEventItem,
  DocumentSummary,
  DocumentTimelineResponse,
  DocumentUploadInfo,
  DocumentUploadStatus,
  IndexStatus,
  JobStatus,
  OcrStatus,
  ParseStatus,
  ProcessingEventStage,
  ReindexDocumentResponse,
  RetryDocumentResponse,
  SourceOrigin,
  SourceType,
  UpdateDocumentTagsRequest,
  UploadMode,
  UploadSessionStatus
} from "@xrag/shared-types";
import { Type } from "class-transformer";
import {
  ArrayMaxSize,
  ArrayUnique,
  IsArray,
  IsIn,
  IsInt,
  IsISO8601,
  IsOptional,
  IsString,
  Max,
  MaxLength,
  Min,
  MinLength
} from "class-validator";

const PARSE_STATUS_VALUES: ParseStatus[] = ["pending", "processing", "success", "failed"];
const OCR_STATUS_VALUES: OcrStatus[] = ["not_required", "queued", "processing", "success", "failed"];
const INDEX_STATUS_VALUES: IndexStatus[] = ["not_indexed", "queued", "chunking", "embedding", "ready", "failed", "stale"];
const SOURCE_TYPE_VALUES: SourceType[] = ["text", "file", "pdf", "link"];
const SOURCE_ORIGIN_VALUES: SourceOrigin[] = ["manual_input", "upload", "link"];
const DOCUMENT_UPLOAD_STATUS_VALUES: DocumentUploadStatus[] = [
  "draft",
  "initiated",
  "uploading",
  "verifying",
  "uploaded",
  "failed"
];
const JOB_STATUS_VALUES: JobStatus[] = ["queued", "running", "succeeded", "failed", "dead"];
const UPLOAD_MODE_VALUES: UploadMode[] = ["single", "multipart"];
const UPLOAD_SESSION_STATUS_VALUES: UploadSessionStatus[] = [
  "initiated",
  "uploading",
  "verifying",
  "uploaded",
  "failed",
  "expired"
];
const PROCESSING_EVENT_STAGE_VALUES: ProcessingEventStage[] = [
  "upload",
  "parse",
  "ocr",
  "fetch",
  "projection",
  "ops",
  "index"
];
const DIAGNOSIS_CODE_VALUES: DiagnosisCode[] = [
  "storage_presign_failed",
  "multipart_part_failed",
  "upload_complete_invalid_parts",
  "object_missing_on_complete",
  "pdf_parse_runtime_error",
  "pdf_parse_unsupported",
  "pdf_parse_timeout",
  "pdf_parse_empty_text",
  "queue_backlog",
  "ocr_runtime_error",
  "ocr_timeout",
  "ocr_no_text_detected",
  "link_fetch_timeout",
  "link_fetch_blocked",
  "link_extract_empty",
  "link_invalid_url",
  "search_projection_stale",
  "index_chunk_failed",
  "index_embedding_failed",
  "retrieval_no_hits",
  "retrieval_scope_empty",
  "answer_insufficient_evidence",
  "citation_missing",
  "provider_timeout"
];

export class CreateTextDocumentRequestDto implements CreateTextDocumentRequest {
  @ApiProperty({ type: String, example: "RAG 产品最小闭环拆解" })
  @IsString()
  @MinLength(1)
  @MaxLength(255)
  title!: string;

  @ApiProperty({ type: String, example: "这里是一段用户手动输入的正文" })
  @IsString()
  @MinLength(1)
  content!: string;

  @ApiProperty({ type: () => String, isArray: true, example: ["RAG", "MVP", "检索"] })
  @IsArray()
  @ArrayUnique()
  @ArrayMaxSize(20)
  @IsString({ each: true })
  tags!: string[];
}

export class UpdateDocumentTagsRequestDto implements UpdateDocumentTagsRequest {
  @ApiProperty({ type: () => String, isArray: true, example: ["用户研究", "访谈", "P1"] })
  @IsArray()
  @ArrayUnique()
  @ArrayMaxSize(20)
  @IsString({ each: true })
  tags!: string[];
}

export class CreateTextDocumentResponseDto implements CreateTextDocumentResponse {
  @ApiProperty({ type: String })
  id!: string;

  @ApiProperty({ type: String, enum: PARSE_STATUS_VALUES })
  parse_status!: ParseStatus;
}

export class CreateLinkDocumentRequestDto implements CreateLinkDocumentRequest {
  @ApiPropertyOptional({ type: String, example: "站点公告：春季产品路线图" })
  @IsOptional()
  @IsString()
  @MinLength(1)
  @MaxLength(255)
  title?: string;

  @ApiProperty({ type: String, example: "https://news.example.com/roadmap" })
  @IsString()
  @MinLength(1)
  source_url!: string;

  @ApiProperty({ type: () => String, isArray: true, example: ["链接", "路线图"] })
  @IsArray()
  @ArrayUnique()
  @ArrayMaxSize(20)
  @IsString({ each: true })
  tags!: string[];
}

export class CreateLinkDocumentResponseDto implements CreateLinkDocumentResponse {
  @ApiProperty({ type: String })
  id!: string;

  @ApiProperty({ type: String, enum: PARSE_STATUS_VALUES })
  parse_status!: ParseStatus;

  @ApiPropertyOptional({ type: String, enum: DIAGNOSIS_CODE_VALUES, nullable: true })
  diagnosis_code!: DiagnosisCode | null;
}

export class RetryDocumentResponseDto implements RetryDocumentResponse {
  @ApiProperty({ type: String })
  document_id!: string;

  @ApiProperty({ type: String })
  job_id!: string;

  @ApiProperty({ type: String, enum: PARSE_STATUS_VALUES })
  parse_status!: ParseStatus;

  @ApiPropertyOptional({ type: String, enum: DIAGNOSIS_CODE_VALUES, nullable: true })
  diagnosis_code!: DiagnosisCode | null;
}

export class ReindexDocumentResponseDto implements ReindexDocumentResponse {
  @ApiProperty({ type: String })
  document_id!: string;

  @ApiProperty({ type: String })
  job_id!: string;

  @ApiProperty({ type: String, enum: INDEX_STATUS_VALUES })
  index_status!: IndexStatus;

  @ApiPropertyOptional({ type: String, enum: DIAGNOSIS_CODE_VALUES, nullable: true })
  diagnosis_code!: DiagnosisCode | null;
}

export class ListDocumentsQueryDto {
  @ApiPropertyOptional({ type: String })
  @IsOptional()
  @IsString()
  q?: string;

  @ApiPropertyOptional({ type: String, enum: SOURCE_TYPE_VALUES })
  @IsOptional()
  @IsIn(SOURCE_TYPE_VALUES)
  source_type?: SourceType;

  @ApiPropertyOptional({ type: String, enum: OCR_STATUS_VALUES })
  @IsOptional()
  @IsIn(OCR_STATUS_VALUES)
  ocr_status?: OcrStatus;

  @ApiPropertyOptional({ type: String, example: "pending,failed" })
  @IsOptional()
  @IsString()
  parse_status?: string;

  @ApiPropertyOptional({ type: String, enum: INDEX_STATUS_VALUES })
  @IsOptional()
  @IsIn(INDEX_STATUS_VALUES)
  index_status?: IndexStatus;

  @ApiPropertyOptional({ type: String, example: "uploaded,failed" })
  @IsOptional()
  @IsString()
  upload_status?: string;

  @ApiPropertyOptional({ type: String, enum: DIAGNOSIS_CODE_VALUES })
  @IsOptional()
  @IsIn(DIAGNOSIS_CODE_VALUES)
  diagnosis_code?: DiagnosisCode;

  @ApiPropertyOptional({ type: String, example: "RAG,MVP" })
  @IsOptional()
  @IsString()
  tags?: string;

  @ApiPropertyOptional({ type: String, example: "2026-03-01T00:00:00.000Z" })
  @IsOptional()
  @IsISO8601()
  date_from?: string;

  @ApiPropertyOptional({ type: String, example: "2026-03-31T23:59:59.999Z" })
  @IsOptional()
  @IsISO8601()
  date_to?: string;

  @ApiPropertyOptional({ type: Number, default: 1 })
  @Type(() => Number)
  @IsOptional()
  @IsInt()
  @Min(1)
  page = 1;

  @ApiPropertyOptional({ type: Number, default: 20, maximum: 100 })
  @Type(() => Number)
  @IsOptional()
  @IsInt()
  @Min(1)
  @Max(100)
  page_size = 20;
}

export class DocumentUploadInfoDto implements DocumentUploadInfo {
  @ApiProperty({ type: String })
  id!: string;

  @ApiProperty({ type: String, enum: UPLOAD_MODE_VALUES })
  upload_mode!: UploadMode;

  @ApiProperty({ type: String, enum: UPLOAD_SESSION_STATUS_VALUES })
  status!: UploadSessionStatus;

  @ApiPropertyOptional({ type: Number, nullable: true })
  part_count!: number | null;

  @ApiProperty({ type: Number })
  uploaded_part_count!: number;

  @ApiPropertyOptional({ type: String, nullable: true })
  verified_at!: string | null;
}

export class DocumentLatestJobInfoDto implements DocumentLatestJobInfo {
  @ApiProperty({ type: String })
  id!: string;

  @ApiProperty({ type: String, enum: JOB_STATUS_VALUES })
  status!: JobStatus;

  @ApiPropertyOptional({ type: String, enum: DIAGNOSIS_CODE_VALUES, nullable: true })
  diagnosis_code!: DiagnosisCode | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  finished_at!: string | null;
}

export class DocumentSummaryDto implements DocumentSummary {
  @ApiProperty({ type: String })
  id!: string;

  @ApiProperty({ type: String })
  title!: string;

  @ApiProperty({ type: String })
  content_preview!: string;

  @ApiProperty({ type: () => String, isArray: true })
  tags!: string[];

  @ApiProperty({ type: String, enum: SOURCE_TYPE_VALUES })
  source_type!: SourceType;

  @ApiProperty({ type: String, enum: SOURCE_ORIGIN_VALUES })
  source_origin!: SourceOrigin;

  @ApiPropertyOptional({ type: String, nullable: true })
  source_url!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  file_name!: string | null;

  @ApiProperty({ type: String, enum: PARSE_STATUS_VALUES })
  parse_status!: ParseStatus;

  @ApiProperty({ type: String, enum: INDEX_STATUS_VALUES })
  index_status!: IndexStatus;

  @ApiPropertyOptional({ type: String, nullable: true })
  indexed_at!: string | null;

  @ApiProperty({ type: Boolean })
  citation_ready!: boolean;

  @ApiPropertyOptional({ type: String, enum: OCR_STATUS_VALUES, nullable: true })
  ocr_status!: OcrStatus | null;

  @ApiPropertyOptional({ type: String, enum: DOCUMENT_UPLOAD_STATUS_VALUES, nullable: true })
  upload_status!: DocumentUploadStatus | null;

  @ApiPropertyOptional({ type: String, enum: DIAGNOSIS_CODE_VALUES, nullable: true })
  diagnosis_code!: DiagnosisCode | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  diagnosis_summary!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  match_explanation!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  ranking_hint!: string | null;

  @ApiPropertyOptional({ type: () => String, isArray: true, nullable: true })
  matched_fields!: string[] | null;

  @ApiPropertyOptional({ type: String, enum: JOB_STATUS_VALUES, nullable: true })
  latest_job_status!: JobStatus | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  page_count: number | null = null;

  @ApiPropertyOptional({ type: String, nullable: true })
  parser_name: string | null = null;

  @ApiProperty({ type: String })
  imported_at!: string;
}

export class DocumentDetailDto extends DocumentSummaryDto implements DocumentDetail {
  @ApiPropertyOptional({ type: String, nullable: true })
  content_raw!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  content_clean!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  mime_type!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  parse_error_message!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  ocr_engine!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  ocr_language!: string | null;

  @ApiPropertyOptional({ type: () => DocumentUploadInfoDto, nullable: true })
  upload!: DocumentUploadInfo | null;

  @ApiPropertyOptional({ type: () => DocumentLatestJobInfoDto, nullable: true })
  latest_job!: DocumentLatestJobInfo | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  last_incident_ref!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  index_version!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  parser_version!: string | null;

  @ApiProperty({ type: String })
  created_at!: string;
}

export class DocumentListResponseDto implements DocumentListResponse {
  @ApiProperty({ type: () => DocumentSummaryDto, isArray: true })
  items!: DocumentSummaryDto[];

  @ApiProperty({ type: Number })
  page!: number;

  @ApiProperty({ type: Number })
  page_size!: number;

  @ApiProperty({ type: Number })
  total!: number;
}

export class DocumentEvidenceItemDto implements DocumentEvidenceItem {
  @ApiProperty({ type: String })
  chunk_id!: string;

  @ApiProperty({ type: Number })
  chunk_index!: number;

  @ApiPropertyOptional({ type: String, nullable: true })
  section_label!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  page_ref!: string | null;

  @ApiProperty({ type: String })
  quote_text!: string;

  @ApiPropertyOptional({ type: Object, nullable: true, additionalProperties: true })
  locator!: Record<string, unknown> | null;
}

export class DocumentEvidenceResponseDto implements DocumentEvidenceResponse {
  @ApiProperty({ type: String })
  document_id!: string;

  @ApiProperty({ type: String, enum: INDEX_STATUS_VALUES })
  index_status!: IndexStatus;

  @ApiProperty({ type: Boolean })
  citation_ready!: boolean;

  @ApiProperty({ type: () => DocumentEvidenceItemDto, isArray: true })
  items!: DocumentEvidenceItemDto[];
}

export class DocumentProcessingEventItemDto implements DocumentProcessingEventItem {
  @ApiProperty({ type: String })
  event_type!: string;

  @ApiProperty({ type: String, enum: PROCESSING_EVENT_STAGE_VALUES })
  stage!: DocumentProcessingEventItem["stage"];

  @ApiProperty({ type: String, enum: PARSE_STATUS_VALUES })
  status!: ParseStatus;

  @ApiPropertyOptional({ type: String, enum: DIAGNOSIS_CODE_VALUES, nullable: true })
  diagnosis_code!: DiagnosisCode | null;

  @ApiProperty({ type: String })
  summary!: string;

  @ApiProperty({ type: String })
  created_at!: string;
}

export class DocumentTimelineResponseDto implements DocumentTimelineResponse {
  @ApiProperty({ type: String })
  document_id!: string;

  @ApiProperty({ type: () => DocumentProcessingEventItemDto, isArray: true })
  items!: DocumentProcessingEventItemDto[];
}
