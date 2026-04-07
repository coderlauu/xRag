import { ApiProperty, ApiPropertyOptional } from "@nestjs/swagger";
import type { DiagnosisCode, DocumentJobType, JobStatus, JobStatusResponse } from "@xrag/shared-types";

const JOB_TYPE_VALUES: DocumentJobType[] = [
  "parse_document",
  "reparse_document",
  "refresh_search_projection",
  "run_ocr",
  "fetch_link",
  "rebuild_search_projection"
];
const JOB_STATUS_VALUES: JobStatus[] = ["queued", "running", "succeeded", "failed", "dead"];
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
  "search_projection_stale"
];

export class JobStatusResponseDto implements JobStatusResponse {
  @ApiProperty({ type: String })
  id!: string;

  @ApiProperty({ type: String })
  document_id!: string;

  @ApiProperty({ type: String, enum: JOB_TYPE_VALUES })
  job_type!: DocumentJobType;

  @ApiProperty({ type: String, enum: JOB_STATUS_VALUES })
  status!: JobStatus;

  @ApiProperty({ type: Number })
  attempt!: number;

  @ApiPropertyOptional({ type: String, nullable: true })
  error_message!: string | null;

  @ApiPropertyOptional({ type: String, enum: DIAGNOSIS_CODE_VALUES, nullable: true })
  diagnosis_code!: DiagnosisCode | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  incident_ref!: string | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  runtime_ms!: number | null;
}
