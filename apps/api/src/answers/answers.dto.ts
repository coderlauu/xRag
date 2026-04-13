import { ApiProperty, ApiPropertyOptional } from "@nestjs/swagger";
import type {
  AnswerClaimFreshnessBadge,
  AnswerScopeMode,
  AnswerSessionStatus,
  DiagnosisCode,
  RetrievalExclusionReason,
  RetrievalMode,
  SourceType
} from "@xrag/shared-types";
import { Type } from "class-transformer";
import {
  ArrayMaxSize,
  IsArray,
  IsBoolean,
  IsDateString,
  IsIn,
  IsInt,
  IsObject,
  IsOptional,
  IsString,
  IsUUID,
  Max,
  MaxLength,
  Min,
  MinLength,
  ValidateNested
} from "class-validator";

const ANSWER_SCOPE_MODE_VALUES: AnswerScopeMode[] = ["global", "search_result", "document"];
const ANSWER_SESSION_STATUS_VALUES: AnswerSessionStatus[] = [
  "idle",
  "retrieving",
  "synthesizing",
  "answered",
  "needs_scope",
  "refused",
  "failed"
];
const RETRIEVAL_MODE_VALUES: RetrievalMode[] = ["hybrid"];
const SOURCE_TYPE_VALUES: SourceType[] = ["text", "file", "pdf", "link"];
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
const RETRIEVAL_EXCLUSION_REASON_VALUES: RetrievalExclusionReason[] = [
  "deduplicated",
  "rerank_cutoff",
  "answer_budget",
  "low_support",
  "citation_unready"
];
const ANSWER_CLAIM_FRESHNESS_BADGE_VALUES: AnswerClaimFreshnessBadge[] = ["ready", "stale_risk", "unknown"];

export class ScopeFilterSetDto {
  @ApiPropertyOptional({ type: [String], maxItems: 20 })
  @IsOptional()
  @IsArray()
  @ArrayMaxSize(20)
  @IsString({ each: true })
  tags?: string[];

  @ApiPropertyOptional({ type: [String], enum: SOURCE_TYPE_VALUES, maxItems: 4 })
  @IsOptional()
  @IsArray()
  @ArrayMaxSize(4)
  @IsIn(SOURCE_TYPE_VALUES, { each: true })
  source_types?: SourceType[];

  @ApiPropertyOptional({ type: String, format: "date-time" })
  @IsOptional()
  @IsDateString()
  date_from?: string;

  @ApiPropertyOptional({ type: String, format: "date-time" })
  @IsOptional()
  @IsDateString()
  date_to?: string;
}

export class AnswerScopePayloadDto {
  @ApiPropertyOptional({ type: String, nullable: true })
  @IsOptional()
  @IsString()
  document_id?: string;

  @ApiPropertyOptional({ type: [String], nullable: true, maxItems: 100 })
  @IsOptional()
  @IsArray()
  @ArrayMaxSize(100)
  @IsString({ each: true })
  document_ids?: string[];

  @ApiPropertyOptional({ type: Boolean, nullable: true })
  @IsOptional()
  @IsBoolean()
  truncated?: boolean;

  @ApiPropertyOptional({ type: String, nullable: true })
  @IsOptional()
  @IsString()
  query?: string | null;

  @ApiPropertyOptional({ type: () => ScopeFilterSetDto, nullable: true })
  @IsOptional()
  @ValidateNested()
  @Type(() => ScopeFilterSetDto)
  filters?: ScopeFilterSetDto | null;
}

export class AnswerScopeDto {
  @ApiProperty({ type: String, enum: ANSWER_SCOPE_MODE_VALUES })
  @IsIn(ANSWER_SCOPE_MODE_VALUES)
  mode!: AnswerScopeMode;

  @ApiPropertyOptional({ type: () => AnswerScopePayloadDto, nullable: true })
  @IsOptional()
  @ValidateNested()
  @Type(() => AnswerScopePayloadDto)
  payload!: AnswerScopePayloadDto | null;
}

export class CreateAnswerRequestDto {
  @ApiProperty({ type: String, example: "接下来两个月最值得投入的能力是什么？" })
  @IsString()
  @MinLength(1)
  @MaxLength(2000)
  question!: string;

  @ApiProperty({ type: () => AnswerScopeDto })
  @ValidateNested()
  @Type(() => AnswerScopeDto)
  scope!: AnswerScopeDto;

  @ApiPropertyOptional({ type: String, format: "uuid", nullable: true })
  @IsOptional()
  @IsUUID()
  continued_from_session_id?: string;
}

export class ListAnswerSessionsQueryDto {
  @ApiPropertyOptional({ type: Number, minimum: 1, default: 1 })
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  page?: number;

  @ApiPropertyOptional({ type: Number, minimum: 1, maximum: 100, default: 20 })
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(100)
  page_size?: number;
}

export class CreateAnswerResponseDto {
  @ApiProperty({ type: String })
  session_id!: string;

  @ApiProperty({ type: String, enum: ANSWER_SESSION_STATUS_VALUES })
  status!: AnswerSessionStatus;
}

export class AnswerCitationDto {
  @ApiProperty({ type: String })
  document_id!: string;

  @ApiProperty({ type: String })
  chunk_id!: string;

  @ApiProperty({ type: String })
  quote_text!: string;

  @ApiPropertyOptional({ type: Object, nullable: true, additionalProperties: true })
  locator!: Record<string, unknown> | null;
}

export class AnswerEvidenceGroupDto {
  @ApiProperty({ type: String })
  claim_slot!: string;

  @ApiProperty({ type: String })
  claim_text!: string;

  @ApiProperty({ type: String, enum: ANSWER_CLAIM_FRESHNESS_BADGE_VALUES })
  freshness_badge!: AnswerClaimFreshnessBadge;

  @ApiProperty({ type: () => AnswerCitationDto, isArray: true })
  citations!: AnswerCitationDto[];
}

export class AnswerHistoryListItemDto {
  @ApiProperty({ type: String })
  session_id!: string;

  @ApiProperty({ type: String })
  question!: string;

  @ApiProperty({ type: () => AnswerScopeDto })
  scope!: AnswerScopeDto;

  @ApiProperty({ type: String })
  scope_summary!: string;

  @ApiPropertyOptional({ type: String, nullable: true })
  continued_from_session_id!: string | null;

  @ApiProperty({ type: String, enum: ANSWER_SESSION_STATUS_VALUES })
  status!: AnswerSessionStatus;

  @ApiProperty({ type: String, format: "date-time" })
  updated_at!: string;
}

export class ListAnswerSessionsResponseDto {
  @ApiProperty({ type: () => AnswerHistoryListItemDto, isArray: true })
  items!: AnswerHistoryListItemDto[];

  @ApiProperty({ type: Number })
  page!: number;

  @ApiProperty({ type: Number })
  page_size!: number;

  @ApiProperty({ type: Number })
  total!: number;
}

export class AnswerSessionResponseDto {
  @ApiProperty({ type: String })
  session_id!: string;

  @ApiProperty({ type: String })
  question!: string;

  @ApiProperty({ type: () => AnswerScopeDto })
  scope!: AnswerScopeDto;

  @ApiProperty({ type: String })
  scope_summary!: string;

  @ApiPropertyOptional({ type: String, nullable: true })
  continued_from_session_id!: string | null;

  @ApiProperty({ type: String, enum: ANSWER_SESSION_STATUS_VALUES })
  status!: AnswerSessionStatus;

  @ApiPropertyOptional({ type: String, nullable: true })
  answer_summary!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  refusal_reason!: string | null;

  @ApiPropertyOptional({ type: String, enum: DIAGNOSIS_CODE_VALUES, nullable: true })
  diagnosis_code!: DiagnosisCode | null;

  @ApiProperty({ type: String, enum: RETRIEVAL_MODE_VALUES })
  retrieval_mode!: RetrievalMode;

  @ApiProperty({ type: () => AnswerCitationDto, isArray: true })
  citations!: AnswerCitationDto[];

  @ApiProperty({ type: () => AnswerEvidenceGroupDto, isArray: true })
  evidence_groups!: AnswerEvidenceGroupDto[];

  @ApiPropertyOptional({ type: Number, nullable: true })
  latency_ms!: number | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  total_cost_usd!: string | null;

  @ApiProperty({ type: String, format: "date-time" })
  updated_at!: string;
}

export class AnswerRetrievalTraceSummaryDto {
  @ApiProperty({ type: String })
  query_normalized!: string;

  @ApiProperty({ type: Number })
  eligible_document_count!: number;

  @ApiProperty({ type: Number })
  lexical_hit_count!: number;

  @ApiProperty({ type: Number })
  semantic_hit_count!: number;

  @ApiProperty({ type: Number })
  merged_hit_count!: number;

  @ApiProperty({ type: String, enum: RETRIEVAL_MODE_VALUES })
  rerank_strategy!: RetrievalMode;

  @ApiPropertyOptional({ type: Number, nullable: true })
  latency_ms!: number | null;
}

export class AnswerRetrievalTraceItemDto {
  @ApiProperty({ type: String })
  document_id!: string;

  @ApiPropertyOptional({ type: String, nullable: true })
  chunk_id!: string | null;

  @ApiProperty({ type: Number })
  rank!: number;

  @ApiPropertyOptional({ type: Number, nullable: true })
  lexical_score!: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  semantic_score!: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  final_score!: number | null;

  @ApiProperty({ type: Boolean })
  used_in_answer!: boolean;

  @ApiPropertyOptional({ type: String, enum: RETRIEVAL_EXCLUSION_REASON_VALUES, nullable: true })
  exclusion_reason!: RetrievalExclusionReason | null;
}

export class AnswerRetrievalTraceResponseDto {
  @ApiProperty({ type: String })
  session_id!: string;

  @ApiPropertyOptional({ type: () => AnswerRetrievalTraceSummaryDto, nullable: true })
  summary!: AnswerRetrievalTraceSummaryDto | null;

  @ApiProperty({ type: () => AnswerRetrievalTraceItemDto, isArray: true })
  items!: AnswerRetrievalTraceItemDto[];
}
