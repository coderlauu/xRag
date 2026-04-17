import { Controller, Get, Param, Query } from "@nestjs/common";
import { ApiOkResponse, ApiOperation, ApiParam, ApiQuery, ApiTags } from "@nestjs/swagger";
import type { OpsTrendWindow } from "@xrag/shared-types";
import {
  LatestDeploymentResponseDto,
  OPS_DIAGNOSTIC_ORIGIN_VALUES,
  OPS_DIAGNOSTIC_SAMPLE_KIND_VALUES,
  OPS_TREND_WINDOW_VALUES,
  OpsAnswerSummaryResponseDto,
  OpsAnswerSessionReplayResponseDto,
  OpsDeploymentCompareQueryDto,
  OpsDeploymentCompareResponseDto,
  OpsDiagnosticSampleListQueryDto,
  OpsDiagnosticSampleListResponseDto,
  OpsDocumentReplayResponseDto,
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

  @Get("samples")
  @ApiOperation({ summary: "List Phase 3A diagnostic samples" })
  @ApiQuery({ name: "origin", required: true, enum: OPS_DIAGNOSTIC_ORIGIN_VALUES })
  @ApiQuery({ name: "sample_kind", required: false, enum: OPS_DIAGNOSTIC_SAMPLE_KIND_VALUES })
  @ApiQuery({ name: "window", required: false, enum: OPS_TREND_WINDOW_VALUES })
  @ApiQuery({ name: "cluster_key", required: false, type: String })
  @ApiQuery({ name: "deployment_record_id", required: false, type: String })
  @ApiQuery({ name: "page", required: false, type: Number })
  @ApiQuery({ name: "page_size", required: false, type: Number })
  @ApiOkResponse({ type: OpsDiagnosticSampleListResponseDto })
  listDiagnosticSamples(@Query() query: OpsDiagnosticSampleListQueryDto) {
    return this.opsService.listDiagnosticSamples(query);
  }

  @Get("replays/answer-sessions/:sessionId")
  @ApiOperation({ summary: "Replay a single answer session for diagnostics" })
  @ApiParam({ name: "sessionId", type: String })
  @ApiOkResponse({ type: OpsAnswerSessionReplayResponseDto })
  getAnswerSessionReplay(@Param("sessionId") sessionId: string) {
    return this.opsService.getAnswerSessionReplay(sessionId);
  }

  @Get("replays/documents/:documentId")
  @ApiOperation({ summary: "Replay a single document pipeline for diagnostics" })
  @ApiParam({ name: "documentId", type: String })
  @ApiOkResponse({ type: OpsDocumentReplayResponseDto })
  getDocumentReplay(@Param("documentId") documentId: string) {
    return this.opsService.getDocumentReplay(documentId);
  }

  @Get("deployments/compare")
  @ApiOperation({ summary: "Compare samples around a deployment window" })
  @ApiQuery({ name: "deployment_record_id", required: true, type: String })
  @ApiQuery({ name: "window", required: false, enum: OPS_TREND_WINDOW_VALUES })
  @ApiQuery({ name: "sample_kind", required: false, enum: OPS_DIAGNOSTIC_SAMPLE_KIND_VALUES })
  @ApiOkResponse({ type: OpsDeploymentCompareResponseDto })
  getDeploymentCompare(@Query() query: OpsDeploymentCompareQueryDto) {
    return this.opsService.getDeploymentCompare(query);
  }

  @Get("deployments/latest")
  @ApiOperation({ summary: "Get the latest deployment summary" })
  @ApiOkResponse({ type: LatestDeploymentResponseDto })
  getLatestDeployment() {
    return this.opsService.getLatestDeployment();
  }
}
