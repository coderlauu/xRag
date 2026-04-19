import { Body, Controller, Get, HttpCode, Param, ParseUUIDPipe, Post, Query } from "@nestjs/common";
import { ApiBody, ApiOkResponse, ApiOperation, ApiParam, ApiQuery, ApiTags } from "@nestjs/swagger";
import type { OpsTrendWindow } from "@xrag/shared-types";
import {
  LatestDeploymentResponseDto,
  OPS_DIAGNOSTIC_ORIGIN_VALUES,
  OPS_DIAGNOSTIC_SAMPLE_KIND_VALUES,
  OPS_RECOVERY_ACTION_TYPE_VALUES,
  OPS_RECOVERY_CANDIDATE_SOURCE_TYPE_VALUES,
  OPS_RECOVERY_RECOMMENDATION_STATE_VALUES,
  OPS_RECOVERY_RISK_LEVEL_VALUES,
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
  OpsRecoveryActionAuditResponseDto,
  OpsRecoveryActionCreateRequestDto,
  OpsRecoveryActionPreviewRequestDto,
  OpsRecoveryActionPreviewResponseDto,
  OpsRecoveryActionResponseDto,
  OpsRecoveryCandidateListQueryDto,
  OpsRecoveryCandidateListResponseDto,
  OpsRollbackPlanQueryDto,
  OpsRollbackPlanResponseDto,
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
  getAnswerSessionReplay(@Param("sessionId", ParseUUIDPipe) sessionId: string) {
    return this.opsService.getAnswerSessionReplay(sessionId);
  }

  @Get("replays/documents/:documentId")
  @ApiOperation({ summary: "Replay a single document pipeline for diagnostics" })
  @ApiParam({ name: "documentId", type: String })
  @ApiOkResponse({ type: OpsDocumentReplayResponseDto })
  getDocumentReplay(@Param("documentId", ParseUUIDPipe) documentId: string) {
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

  @Get("recovery/candidates")
  @ApiOperation({ summary: "List Phase 3B recovery candidates" })
  @ApiQuery({ name: "source_type", required: false, enum: OPS_RECOVERY_CANDIDATE_SOURCE_TYPE_VALUES })
  @ApiQuery({ name: "source_ref", required: false, type: String })
  @ApiQuery({ name: "action_type", required: false, enum: OPS_RECOVERY_ACTION_TYPE_VALUES })
  @ApiQuery({ name: "risk_level", required: false, enum: OPS_RECOVERY_RISK_LEVEL_VALUES })
  @ApiQuery({ name: "recommendation_state", required: false, enum: OPS_RECOVERY_RECOMMENDATION_STATE_VALUES })
  @ApiQuery({ name: "page", required: false, type: Number })
  @ApiQuery({ name: "page_size", required: false, type: Number })
  @ApiOkResponse({ type: OpsRecoveryCandidateListResponseDto })
  listRecoveryCandidates(@Query() query: OpsRecoveryCandidateListQueryDto) {
    return this.opsService.listRecoveryCandidates(query);
  }

  @Post("recovery/actions/preview")
  @HttpCode(200)
  @ApiOperation({ summary: "Preview a Phase 3B recovery action without executing it" })
  @ApiBody({ type: OpsRecoveryActionPreviewRequestDto })
  @ApiOkResponse({ type: OpsRecoveryActionPreviewResponseDto })
  previewRecoveryAction(@Body() body: OpsRecoveryActionPreviewRequestDto) {
    return this.opsService.previewRecoveryAction(body);
  }

  @Post("recovery/actions")
  @HttpCode(200)
  @ApiOperation({ summary: "Create an operator-approved recovery action" })
  @ApiBody({ type: OpsRecoveryActionCreateRequestDto })
  @ApiOkResponse({ type: OpsRecoveryActionResponseDto })
  createRecoveryAction(@Body() body: OpsRecoveryActionCreateRequestDto) {
    return this.opsService.createRecoveryAction(body);
  }

  @Get("recovery/actions/:actionId")
  @ApiOperation({ summary: "Get a Phase 3B recovery action status" })
  @ApiParam({ name: "actionId", type: String })
  @ApiOkResponse({ type: OpsRecoveryActionResponseDto })
  getRecoveryAction(@Param("actionId", ParseUUIDPipe) actionId: string) {
    return this.opsService.getRecoveryAction(actionId);
  }

  @Get("recovery/actions/:actionId/audit")
  @ApiOperation({ summary: "Get a Phase 3B recovery action audit trail" })
  @ApiParam({ name: "actionId", type: String })
  @ApiOkResponse({ type: OpsRecoveryActionAuditResponseDto })
  getRecoveryActionAudit(@Param("actionId", ParseUUIDPipe) actionId: string) {
    return this.opsService.getRecoveryActionAudit(actionId);
  }

  @Get("recovery/rollback-plan")
  @ApiOperation({ summary: "Build a manual rollback plan from deployment compare evidence" })
  @ApiQuery({ name: "deployment_record_id", required: true, type: String })
  @ApiQuery({ name: "window", required: false, enum: OPS_TREND_WINDOW_VALUES })
  @ApiOkResponse({ type: OpsRollbackPlanResponseDto })
  getRollbackPlan(@Query() query: OpsRollbackPlanQueryDto) {
    return this.opsService.getRollbackPlan(query);
  }

  @Get("deployments/latest")
  @ApiOperation({ summary: "Get the latest deployment summary" })
  @ApiOkResponse({ type: LatestDeploymentResponseDto })
  getLatestDeployment() {
    return this.opsService.getLatestDeployment();
  }
}
