import { ApiProperty, ApiPropertyOptional } from "@nestjs/swagger";
import type {
  CompletedUploadPart,
  DiagnosisCode,
  DocumentUploadStatus,
  ParseStatus,
  UploadCompleteRequest,
  UploadCompleteResponse,
  UploadInitiateRequest,
  UploadInitiateResponse,
  UploadMode,
  UploadPartCompleteRequest,
  UploadPartCompleteResponse,
  UploadPartStatus,
  UploadPartUrlRequest,
  UploadPartUrlResponse,
  UploadSessionStatus
} from "@xrag/shared-types";
import {
  ArrayMaxSize,
  ArrayUnique,
  IsArray,
  IsInt,
  IsOptional,
  IsString,
  MaxLength,
  Min,
  MinLength,
  ValidateNested
} from "class-validator";
import { Type } from "class-transformer";

const PARSE_STATUS_VALUES: ParseStatus[] = ["pending", "processing", "success", "failed"];
const UPLOAD_MODE_VALUES: UploadMode[] = ["single", "multipart"];
const UPLOAD_SESSION_STATUS_VALUES: UploadSessionStatus[] = [
  "initiated",
  "uploading",
  "verifying",
  "uploaded",
  "failed",
  "expired"
];
const DOCUMENT_UPLOAD_STATUS_VALUES: DocumentUploadStatus[] = [
  "draft",
  "initiated",
  "uploading",
  "verifying",
  "uploaded",
  "failed"
];
const UPLOAD_PART_STATUS_VALUES: UploadPartStatus[] = ["initiated", "uploaded", "failed"];
const DIAGNOSIS_CODE_VALUES: DiagnosisCode[] = [
  "storage_presign_failed",
  "multipart_part_failed",
  "upload_complete_invalid_parts",
  "object_missing_on_complete",
  "pdf_parse_runtime_error",
  "pdf_parse_unsupported",
  "pdf_parse_timeout",
  "pdf_parse_empty_text",
  "queue_backlog"
];

export class UploadInitiateRequestDto implements UploadInitiateRequest {
  @ApiProperty({ type: String })
  @IsString()
  @MinLength(1)
  file_name!: string;

  @ApiProperty({ type: String })
  @IsString()
  @MinLength(1)
  @MaxLength(255)
  mime_type!: string;

  @ApiProperty({ type: Number })
  @IsInt()
  @Min(1)
  file_size!: number;

  @ApiPropertyOptional({ type: String })
  @IsOptional()
  @IsString()
  @MinLength(64)
  @MaxLength(64)
  checksum_sha256?: string;
}

export class UploadInitiateResponseDto implements UploadInitiateResponse {
  @ApiProperty({ type: String })
  upload_id!: string;

  @ApiProperty({ type: String, enum: UPLOAD_MODE_VALUES })
  upload_mode!: UploadMode;

  @ApiProperty({ type: String })
  object_key!: string;

  @ApiProperty({ type: String, enum: UPLOAD_SESSION_STATUS_VALUES })
  status!: UploadSessionStatus;

  @ApiPropertyOptional({ type: String, enum: ["presigned_put"] })
  upload_method?: "presigned_put";

  @ApiPropertyOptional({ type: String })
  upload_url?: string;

  @ApiPropertyOptional({ type: "object", additionalProperties: { type: "string" } })
  headers?: Record<string, string>;

  @ApiPropertyOptional({ type: Number })
  part_size_bytes?: number;

  @ApiPropertyOptional({ type: Number })
  part_count?: number;

  @ApiPropertyOptional({ type: String, nullable: true })
  provider_upload_id?: string | null;

  @ApiProperty({ type: Number })
  expires_in!: number;
}

export class UploadPartUrlRequestDto implements UploadPartUrlRequest {
  @ApiProperty({ type: () => Number, isArray: true })
  @IsArray()
  @ArrayUnique()
  @ArrayMaxSize(100)
  @IsInt({ each: true })
  @Min(1, { each: true })
  part_numbers!: number[];
}

export class UploadPartUrlItemDto {
  @ApiProperty({ type: Number })
  part_number!: number;

  @ApiProperty({ type: String })
  upload_url!: string;

  @ApiProperty({ type: "object", additionalProperties: { type: "string" } })
  headers!: Record<string, string>;
}

export class UploadPartUrlResponseDto implements UploadPartUrlResponse {
  @ApiProperty({ type: String })
  upload_id!: string;

  @ApiProperty({ type: () => UploadPartUrlItemDto, isArray: true })
  parts!: UploadPartUrlItemDto[];
}

export class UploadPartCompleteRequestDto implements UploadPartCompleteRequest {
  @ApiProperty({ type: String })
  @IsString()
  @MinLength(1)
  etag!: string;

  @ApiProperty({ type: Number })
  @IsInt()
  @Min(1)
  size_bytes!: number;
}

export class UploadPartCompleteResponseDto implements UploadPartCompleteResponse {
  @ApiProperty({ type: String })
  upload_id!: string;

  @ApiProperty({ type: Number })
  part_number!: number;

  @ApiProperty({ type: String, enum: UPLOAD_PART_STATUS_VALUES })
  status!: UploadPartStatus;

  @ApiProperty({ type: Number })
  uploaded_part_count!: number;
}

export class CompletedUploadPartDto implements CompletedUploadPart {
  @ApiProperty({ type: Number })
  @IsInt()
  @Min(1)
  part_number!: number;

  @ApiProperty({ type: String })
  @IsString()
  @MinLength(1)
  etag!: string;
}

export class UploadCompleteRequestDto implements UploadCompleteRequest {
  @ApiProperty({ type: String })
  @IsString()
  @MinLength(1)
  @MaxLength(255)
  title!: string;

  @ApiProperty({ type: () => String, isArray: true })
  @IsArray()
  @IsString({ each: true })
  tags!: string[];

  @ApiProperty({ type: String })
  @IsString()
  @MinLength(64)
  @MaxLength(64)
  checksum_sha256!: string;

  @ApiPropertyOptional({ type: () => CompletedUploadPartDto, isArray: true })
  @IsOptional()
  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => CompletedUploadPartDto)
  parts?: CompletedUploadPartDto[];
}

export class UploadCompleteResponseDto implements UploadCompleteResponse {
  @ApiProperty({ type: String })
  upload_id!: string;

  @ApiProperty({ type: String })
  document_id!: string;

  @ApiProperty({ type: String })
  job_id!: string;

  @ApiProperty({ type: String, enum: DOCUMENT_UPLOAD_STATUS_VALUES })
  upload_status!: DocumentUploadStatus;

  @ApiProperty({ type: String, enum: PARSE_STATUS_VALUES })
  parse_status!: ParseStatus;

  @ApiPropertyOptional({ type: String, enum: DIAGNOSIS_CODE_VALUES, nullable: true })
  diagnosis_code!: DiagnosisCode | null;
}
