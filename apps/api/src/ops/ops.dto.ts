import { ApiProperty, ApiPropertyOptional } from "@nestjs/swagger";
import type {
  DeploymentSmokeStatus,
  IncidentSeverity,
  IncidentSource,
  IncidentStatus,
  LatestDeploymentResponse,
  OpsHealthService,
  OpsHealthSummaryResponse,
  OpsIncidentListResponse,
  OpsIncidentSummary,
  OpsServiceStatus
} from "@xrag/shared-types";

const OPS_SERVICE_STATUS_VALUES: OpsServiceStatus[] = ["healthy", "warning", "critical"];
const INCIDENT_SOURCE_VALUES: IncidentSource[] = ["upload", "parse", "deploy", "ci"];
const INCIDENT_SEVERITY_VALUES: IncidentSeverity[] = ["low", "medium", "high"];
const INCIDENT_STATUS_VALUES: IncidentStatus[] = ["open", "tracked", "resolved"];
const DEPLOYMENT_SMOKE_STATUS_VALUES: DeploymentSmokeStatus[] = ["passed", "failed", "unknown"];

export class OpsHealthServiceDto implements OpsHealthService {
  @ApiProperty({ type: String })
  name!: string;

  @ApiProperty({ type: String, enum: OPS_SERVICE_STATUS_VALUES })
  status!: OpsServiceStatus;

  @ApiProperty({ type: String })
  detail!: string;
}

export class OpsHealthSummaryResponseDto implements OpsHealthSummaryResponse {
  @ApiProperty({ type: () => OpsHealthServiceDto, isArray: true })
  services!: OpsHealthServiceDto[];

  @ApiProperty({ type: String })
  generated_at!: string;
}

export class OpsIncidentSummaryDto implements OpsIncidentSummary {
  @ApiProperty({ type: String })
  incident_ref!: string;

  @ApiProperty({ type: String, enum: INCIDENT_SOURCE_VALUES })
  source!: IncidentSource;

  @ApiProperty({ type: String, enum: INCIDENT_SEVERITY_VALUES })
  severity!: IncidentSeverity;

  @ApiProperty({ type: String, enum: INCIDENT_STATUS_VALUES })
  status!: IncidentStatus;

  @ApiProperty({ type: String })
  title!: string;

  @ApiProperty({ type: String })
  summary!: string;

  @ApiPropertyOptional({ type: String, nullable: true })
  external_url!: string | null;
}

export class OpsIncidentListResponseDto implements OpsIncidentListResponse {
  @ApiProperty({ type: () => OpsIncidentSummaryDto, isArray: true })
  items!: OpsIncidentSummaryDto[];
}

export class LatestDeploymentResponseDto implements LatestDeploymentResponse {
  @ApiPropertyOptional({ type: String, nullable: true })
  current_image_tag!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  previous_stable_image_tag!: string | null;

  @ApiProperty({ type: String, enum: DEPLOYMENT_SMOKE_STATUS_VALUES })
  last_smoke_status!: DeploymentSmokeStatus;

  @ApiPropertyOptional({ type: String, nullable: true })
  last_smoke_at!: string | null;
}
