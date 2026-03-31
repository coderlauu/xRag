import { ApiProperty } from "@nestjs/swagger";
import type { DocumentJobType, JobStatus, JobStatusResponse } from "@xrag/shared-types";

export class JobStatusResponseDto implements JobStatusResponse {
  @ApiProperty()
  id!: string;

  @ApiProperty()
  document_id!: string;

  @ApiProperty({ enum: ["parse_document", "reparse_document", "refresh_search_projection"] })
  job_type!: DocumentJobType;

  @ApiProperty({ enum: ["queued", "running", "succeeded", "failed", "dead"] })
  status!: JobStatus;

  @ApiProperty()
  attempt!: number;

  @ApiProperty({ nullable: true })
  error_message!: string | null;
}
