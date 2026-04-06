import { ApiProperty } from "@nestjs/swagger";
import type { HealthResponse, ReadinessResponse } from "@xrag/shared-types";

export class HealthResponseDto implements HealthResponse {
  @ApiProperty({ type: String })
  status!: string;
}

export class ReadinessResponseDto implements ReadinessResponse {
  @ApiProperty({ type: String })
  status!: string;

  @ApiProperty({ type: "object", additionalProperties: { type: "string" } })
  checks!: Record<string, string>;
}
