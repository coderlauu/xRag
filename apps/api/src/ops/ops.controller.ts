import { Controller, Get, Query } from "@nestjs/common";
import { ApiOkResponse, ApiOperation, ApiQuery, ApiTags } from "@nestjs/swagger";
import type { OpsTrendWindow } from "@xrag/shared-types";
import {
  LatestDeploymentResponseDto,
  OPS_TREND_WINDOW_VALUES,
  OpsAnswerSummaryResponseDto,
  OpsHealthSummaryResponseDto,
  OpsIncidentListResponseDto,
  OpsOverviewResponseDto,
  OpsTrendsResponseDto
} from "./ops.dto";
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

  @Get("answer-summary")
  @ApiOperation({ summary: "Get answer and indexing summary metrics" })
  @ApiOkResponse({ type: OpsAnswerSummaryResponseDto })
  getAnswerSummary() {
    return this.opsService.getAnswerSummary();
  }

  @Get("overview")
  @ApiOperation({ summary: "Get the Phase 2C operations governance overview" })
  @ApiOkResponse({ type: OpsOverviewResponseDto })
  getOverview() {
    return this.opsService.getOverview();
  }

  @Get("trends")
  @ApiOperation({ summary: "Get Phase 2C operations governance trends" })
  @ApiQuery({ name: "window", required: false, enum: OPS_TREND_WINDOW_VALUES })
  @ApiOkResponse({ type: OpsTrendsResponseDto })
  getTrends(@Query("window") window?: OpsTrendWindow) {
    return this.opsService.getTrends(window);
  }

  @Get("deployments/latest")
  @ApiOperation({ summary: "Get the latest deployment summary" })
  @ApiOkResponse({ type: LatestDeploymentResponseDto })
  getLatestDeployment() {
    return this.opsService.getLatestDeployment();
  }
}
