import { ApiProperty, ApiPropertyOptional } from "@nestjs/swagger";
import type {
  AnswerCitation,
  AnswerRetrievalTraceItem,
  AnswerRetrievalTraceResponse,
  AnswerScope,
  AnswerScopeMode,
  AnswerSessionResponse,
  AnswerSessionStatus,
  CreateAnswerRequest,
  CreateAnswerResponse,
  DiagnosisCode,
  RetrievalMode
} from "@xrag/shared-types";
import { Type } from "class-transformer";
import { IsIn, IsObject, IsOptional, IsString, MaxLength, MinLength, ValidateNested } from "class-validator";

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

export class AnswerScopeDto implements AnswerScope {
  @ApiProperty({ type: String, enum: ANSWER_SCOPE_MODE_VALUES })
  @IsIn(ANSWER_SCOPE_MODE_VALUES)
  mode!: AnswerScopeMode;

  @ApiPropertyOptional({ type: Object, nullable: true, additionalProperties: true })
  @IsOptional()
  @IsObject()
  payload!: Record<string, unknown> | null;
}

export class CreateAnswerRequestDto implements CreateAnswerRequest {
  @ApiProperty({ type: String, example: "接下来两个月最值得投入的能力是什么？" })
  @IsString()
  @MinLength(1)
  @MaxLength(2000)
  question!: string;

  @ApiProperty({ type: () => AnswerScopeDto })
  @ValidateNested()
  @Type(() => AnswerScopeDto)
  scope!: AnswerScopeDto;
}

export class CreateAnswerResponseDto implements CreateAnswerResponse {
  @ApiProperty({ type: String })
  session_id!: string;

  @ApiProperty({ type: String, enum: ANSWER_SESSION_STATUS_VALUES })
  status!: AnswerSessionStatus;
}

export class AnswerCitationDto implements AnswerCitation {
  @ApiProperty({ type: String })
  document_id!: string;

  @ApiProperty({ type: String })
  chunk_id!: string;

  @ApiProperty({ type: String })
  quote_text!: string;

  @ApiPropertyOptional({ type: Object, nullable: true, additionalProperties: true })
  locator!: Record<string, unknown> | null;
}

export class AnswerSessionResponseDto implements AnswerSessionResponse {
  @ApiProperty({ type: String })
  session_id!: string;

  @ApiProperty({ type: String })
  question!: string;

  @ApiProperty({ type: () => AnswerScopeDto })
  scope!: AnswerScopeDto;

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

  @ApiPropertyOptional({ type: Number, nullable: true })
  latency_ms!: number | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  total_cost_usd!: string | null;
}

export class AnswerRetrievalTraceItemDto implements AnswerRetrievalTraceItem {
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

  @ApiPropertyOptional({ type: String, nullable: true })
  exclusion_reason!: string | null;
}

export class AnswerRetrievalTraceResponseDto implements AnswerRetrievalTraceResponse {
  @ApiProperty({ type: String })
  session_id!: string;

  @ApiProperty({ type: () => AnswerRetrievalTraceItemDto, isArray: true })
  items!: AnswerRetrievalTraceItemDto[];
}
