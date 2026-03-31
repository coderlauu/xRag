import { ApiProperty, ApiPropertyOptional } from "@nestjs/swagger";
import type {
  CreateTextDocumentRequest,
  CreateTextDocumentResponse,
  DocumentDetail,
  DocumentListResponse,
  DocumentSummary,
  ParseStatus,
  RetryDocumentResponse,
  SourceOrigin,
  SourceType,
  UpdateDocumentTagsRequest
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

export class CreateTextDocumentRequestDto implements CreateTextDocumentRequest {
  @ApiProperty({ example: "RAG 产品最小闭环拆解" })
  @IsString()
  @MinLength(1)
  @MaxLength(255)
  title!: string;

  @ApiProperty({ example: "这里是一段用户手动输入的正文" })
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
  @ApiProperty()
  id!: string;

  @ApiProperty({ enum: ["pending", "processing", "success", "failed"] })
  parse_status!: ParseStatus;
}

export class RetryDocumentResponseDto implements RetryDocumentResponse {
  @ApiProperty()
  document_id!: string;

  @ApiProperty()
  job_id!: string;

  @ApiProperty({ enum: ["pending", "processing", "success", "failed"] })
  parse_status!: ParseStatus;
}

export class ListDocumentsQueryDto {
  @ApiPropertyOptional()
  @IsOptional()
  @IsString()
  q?: string;

  @ApiPropertyOptional({ enum: ["text", "file", "link"] })
  @IsOptional()
  @IsIn(["text", "file", "link"])
  source_type?: SourceType;

  @ApiPropertyOptional({ example: "pending,failed" })
  @IsOptional()
  @IsString()
  parse_status?: string;

  @ApiPropertyOptional({ example: "RAG,MVP" })
  @IsOptional()
  @IsString()
  tags?: string;

  @ApiPropertyOptional({ example: "2026-03-01T00:00:00.000Z" })
  @IsOptional()
  @IsISO8601()
  date_from?: string;

  @ApiPropertyOptional({ example: "2026-03-31T23:59:59.999Z" })
  @IsOptional()
  @IsISO8601()
  date_to?: string;

  @ApiPropertyOptional({ default: 1 })
  @Type(() => Number)
  @IsOptional()
  @IsInt()
  @Min(1)
  page = 1;

  @ApiPropertyOptional({ default: 20, maximum: 100 })
  @Type(() => Number)
  @IsOptional()
  @IsInt()
  @Min(1)
  @Max(100)
  page_size = 20;
}

export class DocumentSummaryDto implements DocumentSummary {
  @ApiProperty()
  id!: string;

  @ApiProperty()
  title!: string;

  @ApiProperty()
  content_preview!: string;

  @ApiProperty({ type: () => String, isArray: true })
  tags!: string[];

  @ApiProperty({ enum: ["text", "file", "link"] })
  source_type!: SourceType;

  @ApiProperty({ enum: ["manual_input", "upload", "link"] })
  source_origin!: SourceOrigin;

  @ApiPropertyOptional({ nullable: true })
  file_name!: string | null;

  @ApiProperty({ enum: ["pending", "processing", "success", "failed"] })
  parse_status!: ParseStatus;

  @ApiProperty()
  imported_at!: string;
}

export class DocumentDetailDto extends DocumentSummaryDto implements DocumentDetail {
  @ApiPropertyOptional({ nullable: true })
  content_raw!: string | null;

  @ApiPropertyOptional({ nullable: true })
  content_clean!: string | null;

  @ApiPropertyOptional({ nullable: true })
  source_url!: string | null;

  @ApiPropertyOptional({ nullable: true })
  mime_type!: string | null;

  @ApiPropertyOptional({ nullable: true })
  parse_error_message!: string | null;

  @ApiProperty()
  created_at!: string;
}

export class DocumentListResponseDto implements DocumentListResponse {
  @ApiProperty({ type: () => DocumentSummaryDto, isArray: true })
  items!: DocumentSummaryDto[];

  @ApiProperty()
  page!: number;

  @ApiProperty()
  page_size!: number;

  @ApiProperty()
  total!: number;
}
