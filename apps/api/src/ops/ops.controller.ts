import { Controller, Get } from "@nestjs/common";
import { ApiOkResponse, ApiOperation, ApiTags } from "@nestjs/swagger";
import { LatestDeploymentResponseDto, OpsHealthSummaryResponseDto, OpsIncidentListResponseDto } from "./ops.dto";
import { OpsService } from "./ops.service";

@ApiTags("ops")
@Controller("api/v1/ops")
export class OpsController {
  constructor(private readonly opsService: OpsService) {}

  @Get("health-summary")
  @ApiOperation({ summary: "Get a read-only health summary for core services" })
  @ApiOkResponse({ type: OpsHealthSummaryResponseDto })
  getHealthSummary() {
    return this.opsService.getHealthSummary();
  }

  @Get("incidents")
  @ApiOperation({ summary: "List recent incidents" })
  @ApiOkResponse({ type: OpsIncidentListResponseDto })
  listIncidents() {
    return this.opsService.listIncidents();
  }

  @Get("deployments/latest")
  @ApiOperation({ summary: "Get the latest deployment summary" })
  @ApiOkResponse({ type: LatestDeploymentResponseDto })
  getLatestDeployment() {
    return this.opsService.getLatestDeployment();
  }
}
