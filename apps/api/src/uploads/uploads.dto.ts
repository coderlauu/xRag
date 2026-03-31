import { ApiProperty } from "@nestjs/swagger";
import type {
  ParseStatus,
  UploadCompleteRequest,
  UploadCompleteResponse,
  UploadInitiateRequest,
  UploadInitiateResponse
} from "@xrag/shared-types";
import { IsArray, IsInt, IsString, MaxLength, Min, MinLength } from "class-validator";

export class UploadInitiateRequestDto implements UploadInitiateRequest {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  file_name!: string;

  @ApiProperty()
  @IsString()
  @MinLength(1)
  @MaxLength(255)
  mime_type!: string;

  @ApiProperty()
  @IsInt()
  @Min(1)
  file_size!: number;
}

export class UploadInitiateResponseDto implements UploadInitiateResponse {
  @ApiProperty()
  upload_id!: string;

  @ApiProperty()
  object_key!: string;

  @ApiProperty({ enum: ["presigned_put"] })
  upload_method!: "presigned_put";

  @ApiProperty()
  upload_url!: string;

  @ApiProperty({ additionalProperties: { type: "string" } })
  headers!: Record<string, string>;

  @ApiProperty()
  expires_in!: number;
}

export class UploadCompleteRequestDto implements UploadCompleteRequest {
  @ApiProperty()
  @IsString()
  @MinLength(1)
  @MaxLength(255)
  title!: string;

  @ApiProperty({ type: () => String, isArray: true })
  @IsArray()
  @IsString({ each: true })
  tags!: string[];

  @ApiProperty()
  @IsString()
  @MinLength(64)
  @MaxLength(64)
  checksum_sha256!: string;
}

export class UploadCompleteResponseDto implements UploadCompleteResponse {
  @ApiProperty()
  document_id!: string;

  @ApiProperty()
  job_id!: string;

  @ApiProperty({ enum: ["pending", "processing", "success", "failed"] })
  parse_status!: ParseStatus;
}
