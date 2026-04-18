import { ApiProperty, ApiPropertyOptional } from "@nestjs/swagger";
import type {
  DeploymentSmokeStatus,
  IncidentSeverity,
  IncidentSource,
  IncidentStatus,
  LatestDeploymentResponse,
  OpsAnswerSessionReplayRelatedContext,
  OpsAnswerSessionReplayResponse,
  OpsAnswerSummaryResponse,
  OpsDeploymentCompareBaseline,
  OpsDeploymentCompareDeltaSummary,
  OpsDeploymentCompareDeployment,
  OpsDeploymentCompareQuery,
  OpsDeploymentCompareResponse,
  OpsDeploymentCompareWindow,
  OpsDiagnosticOrigin,
  OpsDiagnosticSample,
  OpsDiagnosticSampleKind,
  OpsDiagnosticSampleListQuery,
  OpsDiagnosticSampleListResponse,
  OpsDocumentReplayRelatedContext,
  OpsDocumentReplayResponse,
  OpsEvaluationQualitySummary,
  OpsGovernanceNotice,
  OpsHealthService,
  OpsHealthSummaryResponse,
  OpsIncidentCluster,
  OpsIncidentListResponse,
  OpsIncidentSummary,
  OpsIncidentSummaryBlock,
  OpsOverviewResponse,
  OpsReadinessBlockingReason,
  OpsReadinessSnapshot,
  OpsRecommendedAction,
  OpsRecommendedActionCode,
  OpsRegressionClass,
  OpsReleaseGuard,
  OpsReleaseGuardRiskLevel,
  OpsReplayFreshnessFlag,
  OpsReplayRef,
  OpsRecoveryActionCreateRequest,
  OpsRecoveryActionAuditResponse,
  OpsRecoveryActionPreviewRequest,
  OpsRecoveryActionPreviewResponse,
  OpsRecoveryActionResponse,
  OpsRecoveryActionStatus,
  OpsRecoveryActionType,
  OpsRecoveryCandidate,
  OpsRecoveryCandidateListQuery,
  OpsRecoveryCandidateListResponse,
  OpsRecoveryCandidateSourceType,
  OpsRecoveryFactSnapshot,
  OpsRecoveryPrecondition,
  OpsRecoveryRecommendationState,
  OpsRecoveryRiskLevel,
  OpsRecoveryStatusTimelineEntry,
  OpsRecoveryTargetRef,
  OpsRecoveryTargetType,
  OpsRollbackPlanConfidence,
  OpsRollbackPlanQuery,
  OpsRollbackPlanResponse,
  OpsServiceStatus,
  OpsRuntimeQualitySummary,
  OpsTrendMetric,
  OpsTrendPoint,
  OpsTrendSeries,
  OpsTrendSource,
  OpsTrendsResponse,
  OpsTrendWindow
} from "@xrag/shared-types";
import { Type } from "class-transformer";
import { IsArray, IsIn, IsInt, IsOptional, IsString, IsUUID, Max, Min, ValidateNested } from "class-validator";
import { AnswerRetrievalTraceResponseDto, AnswerSessionResponseDto } from "../answers/answers.dto";
import { DocumentDetailDto, DocumentEvidenceResponseDto, DocumentTimelineResponseDto } from "../documents/documents.dto";

const OPS_SERVICE_STATUS_VALUES: OpsServiceStatus[] = ["healthy", "warning", "critical"];
const INCIDENT_SOURCE_VALUES: IncidentSource[] = ["upload", "parse", "ocr", "fetch", "projection", "deploy", "ci"];
const INCIDENT_SEVERITY_VALUES: IncidentSeverity[] = ["low", "medium", "high"];
const INCIDENT_STATUS_VALUES: IncidentStatus[] = ["open", "tracked", "resolved"];
const DEPLOYMENT_SMOKE_STATUS_VALUES: DeploymentSmokeStatus[] = ["passed", "failed", "unknown"];
const OPS_READINESS_BLOCKING_REASON_VALUES: OpsReadinessBlockingReason[] = [
  "none",
  "no_ready_documents",
  "indexing_backlog",
  "indexing_failed",
  "stale_corpus"
];
const OPS_RECOMMENDED_ACTION_CODE_VALUES: OpsRecommendedActionCode[] = [
  "inspect_indexing_backlog",
  "inspect_failed_documents",
  "run_backfill_indexing_dry_run",
  "inspect_quality_regression",
  "inspect_incident_cluster",
  "verify_latest_deployment",
  "rollback_to_previous_stable",
  "monitor_without_action"
];
const OPS_RELEASE_GUARD_RISK_LEVEL_VALUES: OpsReleaseGuardRiskLevel[] = ["healthy", "warning", "critical"];
export const OPS_TREND_WINDOW_VALUES: OpsTrendWindow[] = ["24h", "7d", "30d"];
const OPS_TREND_SOURCE_VALUES: OpsTrendSource[] = ["runtime", "evaluation"];
const OPS_TREND_METRIC_VALUES: OpsTrendMetric[] = [
  "citation_coverage",
  "refusal_rate",
  "latency_p95_ms",
  "avg_token_cost_usd",
  "groundedness",
  "refusal_precision",
  "recall_at_10",
  "mrr",
  "hit_in_answer_rate",
  "embedding_backlog",
  "freshness_lag_p95_ms"
];
const OPS_AFFECTED_SURFACE_VALUES: OpsIncidentCluster["affected_surface"][] = [
  "upload",
  "indexing",
  "retrieval",
  "answer",
  "deployment",
  "ci",
  "ops"
];
const OPS_RECOMMENDED_ACTION_SURFACE_VALUES: OpsRecommendedAction["surface"][] = [
  "ops",
  "ask",
  "search",
  "detail",
  "deployment",
  "indexing",
  "evaluation"
];
const OPS_GOVERNANCE_NOTICE_TARGET_VALUES: OpsGovernanceNotice["target"][] = ["ask", "search", "detail"];
export const OPS_DIAGNOSTIC_SAMPLE_KIND_VALUES: OpsDiagnosticSampleKind[] = ["answer_session", "document_pipeline"];
export const OPS_DIAGNOSTIC_ORIGIN_VALUES: OpsDiagnosticOrigin[] = [
  "trend",
  "incident_cluster",
  "release_compare"
];
const OPS_REGRESSION_CLASS_VALUES: OpsRegressionClass[] = ["new_regression", "existing_debt", "unknown"];
const OPS_REPLAY_FRESHNESS_FLAG_VALUES: OpsReplayFreshnessFlag[] = [
  "stale_document",
  "citation_unready",
  "retrieval_scope_empty",
  "unknown"
];
export const OPS_RECOVERY_CANDIDATE_SOURCE_TYPE_VALUES: OpsRecoveryCandidateSourceType[] = [
  "diagnostic_sample",
  "answer_session_replay",
  "document_replay",
  "deployment_compare"
];
export const OPS_RECOVERY_ACTION_TYPE_VALUES: OpsRecoveryActionType[] = [
  "document_reindex",
  "document_retry",
  "answer_diagnostic_rerun"
];
export const OPS_RECOVERY_TARGET_TYPE_VALUES: OpsRecoveryTargetType[] = ["document", "answer_session"];
export const OPS_RECOVERY_RISK_LEVEL_VALUES: OpsRecoveryRiskLevel[] = ["low", "medium", "high"];
export const OPS_RECOVERY_RECOMMENDATION_STATE_VALUES: OpsRecoveryRecommendationState[] = [
  "recommended",
  "available",
  "blocked",
  "not_applicable"
];
export const OPS_RECOVERY_ACTION_STATUS_VALUES: OpsRecoveryActionStatus[] = [
  "queued",
  "running",
  "succeeded",
  "failed",
  "cancelled",
  "blocked"
];
const OPS_ROLLBACK_PLAN_CONFIDENCE_VALUES: OpsRollbackPlanConfidence[] = ["low", "medium", "high"];

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

export class OpsAnswerSummaryResponseDto implements OpsAnswerSummaryResponse {
  @ApiProperty({ type: Number })
  embedding_backlog!: number;

  @ApiProperty({ type: Number })
  ready_document_count!: number;

  @ApiProperty({ type: Number })
  stale_document_count!: number;

  @ApiProperty({ type: Number })
  failed_document_count!: number;

  @ApiPropertyOptional({ type: Number, nullable: true })
  answer_latency_p95!: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  citation_coverage!: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  refusal_rate!: number | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  avg_token_cost_usd!: string | null;
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

export class OpsReadinessSnapshotDto implements OpsReadinessSnapshot {
  @ApiProperty({ type: Number })
  queued_count!: number;

  @ApiProperty({ type: Number })
  chunking_count!: number;

  @ApiProperty({ type: Number })
  embedding_count!: number;

  @ApiProperty({ type: Number })
  ready_count!: number;

  @ApiProperty({ type: Number })
  stale_count!: number;

  @ApiProperty({ type: Number })
  failed_count!: number;

  @ApiProperty({ type: Number })
  total_count!: number;

  @ApiPropertyOptional({ type: Number, nullable: true })
  readiness_rate!: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  freshness_lag_p95_ms!: number | null;

  @ApiProperty({ type: String, enum: OPS_READINESS_BLOCKING_REASON_VALUES })
  blocking_reason!: OpsReadinessBlockingReason;
}

export class OpsRuntimeQualitySummaryDto implements OpsRuntimeQualitySummary {
  @ApiProperty({ type: String, enum: OPS_TREND_WINDOW_VALUES })
  window!: OpsTrendWindow;

  @ApiProperty({ type: Number })
  terminal_session_count!: number;

  @ApiProperty({ type: Number })
  answered_session_count!: number;

  @ApiPropertyOptional({ type: Number, nullable: true })
  latency_p50_ms!: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  latency_p95_ms!: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  citation_coverage!: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  refusal_rate!: number | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  avg_token_cost_usd!: string | null;
}

export class OpsEvaluationQualitySummaryDto implements OpsEvaluationQualitySummary {
  @ApiProperty({ type: String })
  latest_run_ref!: string;

  @ApiProperty({ type: String })
  environment!: string;

  @ApiProperty({ type: String })
  source!: string;

  @ApiProperty({ type: String, enum: ["completed", "failed"] })
  status!: "completed" | "failed";

  @ApiPropertyOptional({ type: String, nullable: true })
  commit_sha!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  dataset_version!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  completed_at!: string | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  recall_at_10!: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  mrr!: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  hit_in_answer_rate!: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  groundedness!: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  citation_coverage!: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  refusal_precision!: number | null;

  @ApiPropertyOptional({ type: Number, nullable: true })
  latency_p95_ms!: number | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  avg_token_cost_usd!: string | null;
}

export class OpsIncidentClusterDto implements OpsIncidentCluster {
  @ApiProperty({ type: String })
  cluster_key!: string;

  @ApiProperty({ type: String, enum: INCIDENT_SOURCE_VALUES })
  source!: IncidentSource;

  @ApiProperty({ type: String, enum: INCIDENT_SEVERITY_VALUES })
  severity!: IncidentSeverity;

  @ApiProperty({ type: String, enum: INCIDENT_STATUS_VALUES })
  status!: IncidentStatus;

  @ApiProperty({ type: Number })
  incident_count!: number;

  @ApiPropertyOptional({ type: String, nullable: true })
  latest_incident_ref!: string | null;

  @ApiProperty({ type: String, enum: OPS_AFFECTED_SURFACE_VALUES })
  affected_surface!: OpsIncidentCluster["affected_surface"];

  @ApiProperty({ type: String, enum: OPS_RECOMMENDED_ACTION_CODE_VALUES })
  recommended_action_code!: OpsRecommendedActionCode;
}

export class OpsIncidentSummaryBlockDto implements OpsIncidentSummaryBlock {
  @ApiProperty({ type: Number })
  open_count!: number;

  @ApiProperty({ type: Number })
  high_risk_count!: number;

  @ApiProperty({ type: () => OpsIncidentClusterDto, isArray: true })
  clusters!: OpsIncidentClusterDto[];
}

export class OpsReleaseGuardDto implements OpsReleaseGuard {
  @ApiProperty({ type: String, enum: OPS_RELEASE_GUARD_RISK_LEVEL_VALUES })
  risk_level!: OpsReleaseGuardRiskLevel;

  @ApiPropertyOptional({ type: String, nullable: true })
  current_image_tag!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  previous_stable_image_tag!: string | null;

  @ApiProperty({ type: String, enum: DEPLOYMENT_SMOKE_STATUS_VALUES })
  smoke_status!: DeploymentSmokeStatus;

  @ApiPropertyOptional({ type: String, nullable: true })
  smoke_at!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  deployed_at!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  workflow_run_id!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  related_evaluation_run_ref!: string | null;

  @ApiProperty({ type: Number })
  related_incident_count!: number;

  @ApiProperty({ type: String })
  summary!: string;
}

export class OpsRecommendedActionDto implements OpsRecommendedAction {
  @ApiProperty({ type: String, enum: OPS_RECOMMENDED_ACTION_CODE_VALUES })
  code!: OpsRecommendedActionCode;

  @ApiProperty({ type: String, enum: INCIDENT_SEVERITY_VALUES })
  priority!: IncidentSeverity;

  @ApiProperty({ type: String, enum: OPS_RECOMMENDED_ACTION_SURFACE_VALUES })
  surface!: OpsRecommendedAction["surface"];

  @ApiProperty({ type: String })
  title!: string;

  @ApiProperty({ type: String })
  summary!: string;
}

export class OpsGovernanceNoticeDto implements OpsGovernanceNotice {
  @ApiProperty({ type: String, enum: OPS_GOVERNANCE_NOTICE_TARGET_VALUES })
  target!: OpsGovernanceNotice["target"];

  @ApiProperty({ type: String, enum: OPS_RELEASE_GUARD_RISK_LEVEL_VALUES })
  level!: OpsReleaseGuardRiskLevel;

  @ApiProperty({
    type: String,
    enum: [...OPS_READINESS_BLOCKING_REASON_VALUES, ...OPS_RECOMMENDED_ACTION_CODE_VALUES]
  })
  code!: OpsReadinessBlockingReason | OpsRecommendedActionCode;

  @ApiProperty({ type: String })
  title!: string;

  @ApiProperty({ type: String })
  summary!: string;
}

export class OpsOverviewResponseDto implements OpsOverviewResponse {
  @ApiProperty({ type: String })
  generated_at!: string;

  @ApiProperty({ type: () => OpsReadinessSnapshotDto })
  readiness!: OpsReadinessSnapshotDto;

  @ApiProperty({ type: () => OpsRuntimeQualitySummaryDto })
  runtime_quality!: OpsRuntimeQualitySummaryDto;

  @ApiPropertyOptional({ type: () => OpsEvaluationQualitySummaryDto, nullable: true })
  evaluation_quality!: OpsEvaluationQualitySummaryDto | null;

  @ApiProperty({ type: () => OpsIncidentSummaryBlockDto })
  incident_summary!: OpsIncidentSummaryBlockDto;

  @ApiProperty({ type: () => OpsReleaseGuardDto })
  release_guard!: OpsReleaseGuardDto;

  @ApiProperty({ type: () => OpsRecommendedActionDto, isArray: true })
  recommended_actions!: OpsRecommendedActionDto[];

  @ApiProperty({ type: () => OpsGovernanceNoticeDto, isArray: true })
  notices!: OpsGovernanceNoticeDto[];
}

export class OpsTrendPointDto implements OpsTrendPoint {
  @ApiProperty({ type: String })
  ts!: string;

  @ApiPropertyOptional({
    type: String,
    nullable: true,
    oneOf: [{ type: "number" }, { type: "string" }]
  })
  value!: number | string | null;
}

export class OpsTrendSeriesDto implements OpsTrendSeries {
  @ApiProperty({ type: String, enum: OPS_TREND_METRIC_VALUES })
  metric!: OpsTrendMetric;

  @ApiProperty({ type: String, enum: OPS_TREND_SOURCE_VALUES })
  source!: OpsTrendSource;

  @ApiProperty({ type: String, enum: ["hour", "day"] })
  granularity!: "hour" | "day";

  @ApiProperty({ type: () => OpsTrendPointDto, isArray: true })
  points!: OpsTrendPointDto[];
}

export class OpsTrendsResponseDto implements OpsTrendsResponse {
  @ApiProperty({ type: String, enum: OPS_TREND_WINDOW_VALUES })
  window!: OpsTrendWindow;

  @ApiProperty({ type: String })
  generated_at!: string;

  @ApiProperty({ type: () => OpsTrendSeriesDto, isArray: true })
  series!: OpsTrendSeriesDto[];
}

export class OpsReplayRefDto implements OpsReplayRef {
  @ApiProperty({ type: String, enum: ["GET"] })
  method!: "GET";

  @ApiProperty({ type: String })
  path!: string;
}

export class OpsDiagnosticSampleDto implements OpsDiagnosticSample {
  @ApiProperty({ type: String })
  sample_id!: string;

  @ApiProperty({ type: String, enum: OPS_DIAGNOSTIC_SAMPLE_KIND_VALUES })
  sample_kind!: OpsDiagnosticSampleKind;

  @ApiProperty({ type: String })
  source_id!: string;

  @ApiProperty({ type: String, enum: OPS_DIAGNOSTIC_ORIGIN_VALUES })
  origin!: OpsDiagnosticOrigin;

  @ApiProperty({ type: String, enum: INCIDENT_SEVERITY_VALUES })
  severity!: IncidentSeverity;

  @ApiProperty({ type: String, format: "date-time" })
  detected_at!: string;

  @ApiProperty({ type: String })
  title!: string;

  @ApiProperty({ type: String })
  summary!: string;

  @ApiPropertyOptional({ type: String, nullable: true })
  related_incident_ref!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  related_deployment_record_id!: string | null;

  @ApiPropertyOptional({ type: String, enum: OPS_REGRESSION_CLASS_VALUES, nullable: true })
  regression_class!: OpsRegressionClass | null;

  @ApiProperty({ type: () => OpsReplayRefDto })
  next_replay_ref!: OpsReplayRefDto;
}

export class OpsDiagnosticSampleListQueryDto implements OpsDiagnosticSampleListQuery {
  @ApiProperty({ type: String, enum: OPS_DIAGNOSTIC_ORIGIN_VALUES })
  @IsIn(OPS_DIAGNOSTIC_ORIGIN_VALUES)
  origin!: OpsDiagnosticOrigin;

  @ApiPropertyOptional({ type: String, enum: OPS_DIAGNOSTIC_SAMPLE_KIND_VALUES })
  @IsOptional()
  @IsIn(OPS_DIAGNOSTIC_SAMPLE_KIND_VALUES)
  sample_kind?: OpsDiagnosticSampleKind;

  @ApiPropertyOptional({ type: String, enum: OPS_TREND_WINDOW_VALUES, default: "24h" })
  @IsOptional()
  @IsIn(OPS_TREND_WINDOW_VALUES)
  window?: OpsTrendWindow;

  @ApiPropertyOptional({ type: String })
  @IsOptional()
  @IsString()
  cluster_key?: string;

  @ApiPropertyOptional({ type: String, format: "uuid" })
  @IsOptional()
  @IsUUID()
  deployment_record_id?: string;

  @ApiPropertyOptional({ type: Number, default: 1, minimum: 1 })
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  page?: number;

  @ApiPropertyOptional({ type: Number, default: 20, minimum: 1, maximum: 100 })
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(100)
  page_size?: number;
}

export class OpsDiagnosticSampleListResponseDto implements OpsDiagnosticSampleListResponse {
  @ApiProperty({ type: String, format: "date-time" })
  generated_at!: string;

  @ApiProperty({ type: String, enum: OPS_DIAGNOSTIC_ORIGIN_VALUES })
  origin!: OpsDiagnosticOrigin;

  @ApiProperty({ type: String, enum: OPS_TREND_WINDOW_VALUES })
  window!: OpsTrendWindow;

  @ApiProperty({ type: Number })
  page!: number;

  @ApiProperty({ type: Number })
  page_size!: number;

  @ApiProperty({ type: Number })
  total!: number;

  @ApiProperty({ type: () => OpsDiagnosticSampleDto, isArray: true })
  items!: OpsDiagnosticSampleDto[];
}

export class OpsAnswerSessionReplayRelatedContextDto implements OpsAnswerSessionReplayRelatedContext {
  @ApiPropertyOptional({ type: String, nullable: true })
  related_incident_ref!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  related_deployment_record_id!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  related_evaluation_run_ref!: string | null;

  @ApiProperty({ type: () => String, enum: OPS_REPLAY_FRESHNESS_FLAG_VALUES, isArray: true })
  freshness_flags!: OpsReplayFreshnessFlag[];
}

export class OpsAnswerSessionReplayResponseDto implements OpsAnswerSessionReplayResponse {
  @ApiProperty({ type: String, format: "date-time" })
  generated_at!: string;

  @ApiProperty({ type: () => OpsDiagnosticSampleDto })
  sample!: OpsDiagnosticSampleDto;

  @ApiProperty({ type: () => AnswerSessionResponseDto })
  session!: OpsAnswerSessionReplayResponse["session"];

  @ApiProperty({ type: () => AnswerRetrievalTraceResponseDto })
  retrieval!: OpsAnswerSessionReplayResponse["retrieval"];

  @ApiProperty({ type: () => OpsAnswerSessionReplayRelatedContextDto })
  related_context!: OpsAnswerSessionReplayRelatedContextDto;
}

export class OpsDocumentReplayRelatedContextDto implements OpsDocumentReplayRelatedContext {
  @ApiPropertyOptional({ type: String, enum: OPS_READINESS_BLOCKING_REASON_VALUES, nullable: true })
  blocking_reason!: OpsReadinessBlockingReason | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  related_incident_ref!: string | null;

  @ApiProperty({ type: Number })
  related_answer_session_count!: number;

  @ApiPropertyOptional({ type: String, nullable: true })
  related_deployment_record_id!: string | null;
}

export class OpsDocumentReplayResponseDto implements OpsDocumentReplayResponse {
  @ApiProperty({ type: String, format: "date-time" })
  generated_at!: string;

  @ApiProperty({ type: () => OpsDiagnosticSampleDto })
  sample!: OpsDiagnosticSampleDto;

  @ApiProperty({ type: () => DocumentDetailDto })
  document!: OpsDocumentReplayResponse["document"];

  @ApiProperty({ type: () => DocumentTimelineResponseDto })
  timeline!: OpsDocumentReplayResponse["timeline"];

  @ApiProperty({ type: () => DocumentEvidenceResponseDto })
  evidence!: OpsDocumentReplayResponse["evidence"];

  @ApiProperty({ type: () => OpsDocumentReplayRelatedContextDto })
  related_context!: OpsDocumentReplayRelatedContextDto;
}

export class OpsDeploymentCompareQueryDto implements OpsDeploymentCompareQuery {
  @ApiProperty({ type: String, format: "uuid" })
  @IsUUID()
  deployment_record_id!: string;

  @ApiPropertyOptional({ type: String, enum: OPS_TREND_WINDOW_VALUES, default: "24h" })
  @IsOptional()
  @IsIn(OPS_TREND_WINDOW_VALUES)
  window?: OpsTrendWindow;

  @ApiPropertyOptional({ type: String, enum: OPS_DIAGNOSTIC_SAMPLE_KIND_VALUES })
  @IsOptional()
  @IsIn(OPS_DIAGNOSTIC_SAMPLE_KIND_VALUES)
  sample_kind?: OpsDiagnosticSampleKind;
}

export class OpsDeploymentCompareDeploymentDto implements OpsDeploymentCompareDeployment {
  @ApiProperty({ type: String })
  deployment_record_id!: string;

  @ApiProperty({ type: String })
  environment!: string;

  @ApiPropertyOptional({ type: String, nullable: true })
  commit_sha!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  workflow_run_id!: string | null;

  @ApiProperty({ type: String })
  current_image_tag!: string;

  @ApiPropertyOptional({ type: String, nullable: true })
  previous_stable_image_tag!: string | null;

  @ApiProperty({ type: String, enum: DEPLOYMENT_SMOKE_STATUS_VALUES })
  smoke_status!: DeploymentSmokeStatus;

  @ApiPropertyOptional({ type: String, nullable: true })
  smoke_at!: string | null;

  @ApiProperty({ type: String, format: "date-time" })
  deployed_at!: string;

  @ApiPropertyOptional({ type: String, nullable: true })
  evidence_url!: string | null;
}

export class OpsDeploymentCompareBaselineDto implements OpsDeploymentCompareBaseline {
  @ApiPropertyOptional({ type: String, nullable: true })
  previous_stable_image_tag!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  previous_deployment_record_id!: string | null;

  @ApiPropertyOptional({ type: String, nullable: true })
  related_evaluation_run_ref!: string | null;
}

export class OpsDeploymentCompareWindowDto implements OpsDeploymentCompareWindow {
  @ApiProperty({ type: String, format: "date-time" })
  start_at!: string;

  @ApiProperty({ type: String, format: "date-time" })
  end_at!: string;

  @ApiProperty({ type: Number })
  sample_count!: number;

  @ApiProperty({ type: Number })
  high_severity_count!: number;
}

export class OpsDeploymentCompareDeltaSummaryDto implements OpsDeploymentCompareDeltaSummary {
  @ApiProperty({ type: Number })
  regression_count!: number;

  @ApiProperty({ type: Number })
  new_regression_count!: number;

  @ApiProperty({ type: Number })
  existing_debt_count!: number;

  @ApiProperty({ type: Number })
  affected_answer_session_count!: number;

  @ApiProperty({ type: Number })
  affected_document_count!: number;

  @ApiProperty({ type: String })
  summary!: string;
}

export class OpsDeploymentCompareResponseDto implements OpsDeploymentCompareResponse {
  @ApiProperty({ type: String, format: "date-time" })
  generated_at!: string;

  @ApiProperty({ type: () => OpsDeploymentCompareDeploymentDto })
  deployment!: OpsDeploymentCompareDeploymentDto;

  @ApiProperty({ type: () => OpsDeploymentCompareBaselineDto })
  baseline!: OpsDeploymentCompareBaselineDto;

  @ApiProperty({ type: () => OpsDeploymentCompareWindowDto })
  before_window!: OpsDeploymentCompareWindowDto;

  @ApiProperty({ type: () => OpsDeploymentCompareWindowDto })
  after_window!: OpsDeploymentCompareWindowDto;

  @ApiProperty({ type: () => OpsDeploymentCompareDeltaSummaryDto })
  delta_summary!: OpsDeploymentCompareDeltaSummaryDto;

  @ApiProperty({ type: () => OpsDiagnosticSampleDto, isArray: true })
  affected_samples!: OpsDiagnosticSampleDto[];
}

export class OpsRecoveryTargetRefDto implements OpsRecoveryTargetRef {
  @ApiProperty({ type: String, enum: OPS_RECOVERY_TARGET_TYPE_VALUES })
  @IsIn(OPS_RECOVERY_TARGET_TYPE_VALUES)
  type!: OpsRecoveryTargetType;

  @ApiProperty({ type: String })
  @IsString()
  id!: string;
}

export class OpsRecoveryFactSnapshotDto implements OpsRecoveryFactSnapshot {
  @ApiProperty({ type: String, format: "date-time" })
  captured_at!: string;

  @ApiProperty({ type: String, enum: OPS_RECOVERY_TARGET_TYPE_VALUES })
  target_type!: OpsRecoveryTargetType;

  @ApiProperty({ type: () => OpsRecoveryTargetRefDto, isArray: true })
  target_refs!: OpsRecoveryTargetRefDto[];

  @ApiProperty({ type: Object })
  facts!: Record<string, unknown>;
}

export class OpsRecoveryPreconditionDto implements OpsRecoveryPrecondition {
  @ApiProperty({ type: String })
  code!: string;

  @ApiProperty({ type: String })
  label!: string;

  @ApiProperty({ type: Boolean })
  satisfied!: boolean;

  @ApiPropertyOptional({ type: String, nullable: true })
  detail!: string | null;
}

export class OpsRecoveryCandidateDto implements OpsRecoveryCandidate {
  @ApiProperty({ type: String })
  candidate_id!: string;

  @ApiProperty({ type: String, enum: OPS_RECOVERY_CANDIDATE_SOURCE_TYPE_VALUES })
  source_type!: OpsRecoveryCandidateSourceType;

  @ApiProperty({ type: String })
  source_ref!: string;

  @ApiProperty({ type: String, enum: OPS_RECOVERY_ACTION_TYPE_VALUES })
  action_type!: OpsRecoveryActionType;

  @ApiProperty({ type: String, enum: OPS_RECOVERY_TARGET_TYPE_VALUES })
  target_type!: OpsRecoveryTargetType;

  @ApiProperty({ type: () => OpsRecoveryTargetRefDto, isArray: true })
  target_refs!: OpsRecoveryTargetRefDto[];

  @ApiProperty({ type: String, enum: OPS_RECOVERY_RISK_LEVEL_VALUES })
  risk_level!: OpsRecoveryRiskLevel;

  @ApiProperty({ type: String, enum: OPS_RECOVERY_RECOMMENDATION_STATE_VALUES })
  recommendation_state!: OpsRecoveryRecommendationState;

  @ApiProperty({ type: String })
  title!: string;

  @ApiProperty({ type: String })
  summary!: string;

  @ApiProperty({ type: () => OpsRecoveryPreconditionDto, isArray: true })
  preconditions!: OpsRecoveryPreconditionDto[];

  @ApiPropertyOptional({ type: String, nullable: true })
  blocked_reason!: string | null;

  @ApiProperty({ type: () => OpsReplayRefDto })
  preview_ref!: OpsReplayRefDto;
}

export class OpsRecoveryCandidateListQueryDto implements OpsRecoveryCandidateListQuery {
  @ApiPropertyOptional({ type: String, enum: OPS_RECOVERY_CANDIDATE_SOURCE_TYPE_VALUES })
  @IsOptional()
  @IsIn(OPS_RECOVERY_CANDIDATE_SOURCE_TYPE_VALUES)
  source_type?: OpsRecoveryCandidateSourceType;

  @ApiPropertyOptional({ type: String })
  @IsOptional()
  @IsString()
  source_ref?: string;

  @ApiPropertyOptional({ type: String, enum: OPS_RECOVERY_ACTION_TYPE_VALUES })
  @IsOptional()
  @IsIn(OPS_RECOVERY_ACTION_TYPE_VALUES)
  action_type?: OpsRecoveryActionType;

  @ApiPropertyOptional({ type: String, enum: OPS_RECOVERY_RISK_LEVEL_VALUES })
  @IsOptional()
  @IsIn(OPS_RECOVERY_RISK_LEVEL_VALUES)
  risk_level?: OpsRecoveryRiskLevel;

  @ApiPropertyOptional({ type: String, enum: OPS_RECOVERY_RECOMMENDATION_STATE_VALUES })
  @IsOptional()
  @IsIn(OPS_RECOVERY_RECOMMENDATION_STATE_VALUES)
  recommendation_state?: OpsRecoveryRecommendationState;

  @ApiPropertyOptional({ type: Number, default: 1, minimum: 1 })
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  page?: number;

  @ApiPropertyOptional({ type: Number, default: 20, minimum: 1, maximum: 100 })
  @IsOptional()
  @Type(() => Number)
  @IsInt()
  @Min(1)
  @Max(100)
  page_size?: number;
}

export class OpsRecoveryCandidateListResponseDto implements OpsRecoveryCandidateListResponse {
  @ApiProperty({ type: String, format: "date-time" })
  generated_at!: string;

  @ApiProperty({ type: Number })
  page!: number;

  @ApiProperty({ type: Number })
  page_size!: number;

  @ApiProperty({ type: Number })
  total!: number;

  @ApiProperty({ type: () => OpsRecoveryCandidateDto, isArray: true })
  items!: OpsRecoveryCandidateDto[];
}

export class OpsRecoveryActionPreviewRequestDto implements OpsRecoveryActionPreviewRequest {
  @ApiProperty({ type: String })
  @IsString()
  candidate_id!: string;

  @ApiProperty({ type: String, enum: OPS_RECOVERY_ACTION_TYPE_VALUES })
  @IsIn(OPS_RECOVERY_ACTION_TYPE_VALUES)
  action_type!: OpsRecoveryActionType;

  @ApiProperty({ type: String, enum: OPS_RECOVERY_TARGET_TYPE_VALUES })
  @IsIn(OPS_RECOVERY_TARGET_TYPE_VALUES)
  target_type!: OpsRecoveryTargetType;

  @ApiProperty({ type: () => OpsRecoveryTargetRefDto, isArray: true })
  @IsArray()
  @ValidateNested({ each: true })
  @Type(() => OpsRecoveryTargetRefDto)
  target_refs!: OpsRecoveryTargetRefDto[];
}

export class OpsRecoveryActionPreviewResponseDto implements OpsRecoveryActionPreviewResponse {
  @ApiProperty({ type: String })
  preview_id!: string;

  @ApiProperty({ type: String, format: "date-time" })
  generated_at!: string;

  @ApiProperty({ type: String, format: "date-time" })
  expires_at!: string;

  @ApiProperty({ type: String })
  candidate_id!: string;

  @ApiProperty({ type: String, enum: OPS_RECOVERY_ACTION_TYPE_VALUES })
  action_type!: OpsRecoveryActionType;

  @ApiProperty({ type: String, enum: OPS_RECOVERY_TARGET_TYPE_VALUES })
  target_type!: OpsRecoveryTargetType;

  @ApiProperty({ type: () => OpsRecoveryTargetRefDto, isArray: true })
  target_refs!: OpsRecoveryTargetRefDto[];

  @ApiProperty({ type: String, enum: OPS_RECOVERY_RISK_LEVEL_VALUES })
  risk_level!: OpsRecoveryRiskLevel;

  @ApiProperty({ type: String, enum: OPS_RECOVERY_RECOMMENDATION_STATE_VALUES })
  recommendation_state!: OpsRecoveryRecommendationState;

  @ApiProperty({ type: () => OpsRecoveryPreconditionDto, isArray: true })
  preconditions!: OpsRecoveryPreconditionDto[];

  @ApiPropertyOptional({ type: String, nullable: true })
  blocked_reason!: string | null;

  @ApiProperty({ type: String })
  estimated_blast_radius!: string;

  @ApiProperty({ type: String })
  idempotency_key!: string;

  @ApiProperty({ type: () => OpsRecoveryFactSnapshotDto })
  source_facts!: OpsRecoveryFactSnapshotDto;

  @ApiProperty({ type: () => OpsRecoveryFactSnapshotDto })
  before_facts!: OpsRecoveryFactSnapshotDto;
}

export class OpsRecoveryActionCreateRequestDto implements OpsRecoveryActionCreateRequest {
  @ApiProperty({ type: String })
  @IsString()
  candidate_id!: string;

  @ApiProperty({ type: String })
  @IsString()
  preview_id!: string;

  @ApiProperty({ type: String })
  @IsString()
  idempotency_key!: string;

  @ApiProperty({ type: String })
  @IsString()
  reason!: string;
}

export class OpsRecoveryActionResponseDto implements OpsRecoveryActionResponse {
  @ApiProperty({ type: String, format: "uuid" })
  action_id!: string;

  @ApiPropertyOptional({ type: String, nullable: true })
  candidate_id!: string | null;

  @ApiProperty({ type: String, enum: OPS_RECOVERY_ACTION_STATUS_VALUES })
  status!: OpsRecoveryActionStatus;

  @ApiProperty({ type: String, enum: OPS_RECOVERY_ACTION_TYPE_VALUES })
  action_type!: OpsRecoveryActionType;

  @ApiProperty({ type: String, enum: OPS_RECOVERY_TARGET_TYPE_VALUES })
  target_type!: OpsRecoveryTargetType;

  @ApiProperty({ type: () => OpsRecoveryTargetRefDto, isArray: true })
  target_refs!: OpsRecoveryTargetRefDto[];

  @ApiProperty({ type: () => OpsReplayRefDto, isArray: true })
  queue_job_refs!: OpsReplayRefDto[];

  @ApiPropertyOptional({ type: String, nullable: true })
  diagnosis_code!: OpsRecoveryActionResponse["diagnosis_code"];

  @ApiPropertyOptional({ type: String, nullable: true })
  error_message!: string | null;

  @ApiProperty({ type: String, format: "date-time" })
  created_at!: string;

  @ApiPropertyOptional({ type: String, format: "date-time", nullable: true })
  started_at!: string | null;

  @ApiPropertyOptional({ type: String, format: "date-time", nullable: true })
  completed_at!: string | null;

  @ApiProperty({ type: String, format: "date-time" })
  updated_at!: string;
}

export class OpsRecoveryStatusTimelineEntryDto implements OpsRecoveryStatusTimelineEntry {
  @ApiProperty({ type: String, enum: OPS_RECOVERY_ACTION_STATUS_VALUES })
  status!: OpsRecoveryActionStatus;

  @ApiProperty({ type: String, format: "date-time" })
  at!: string;

  @ApiProperty({ type: String })
  summary!: string;
}

export class OpsRecoveryActionAuditResponseDto implements OpsRecoveryActionAuditResponse {
  @ApiProperty({ type: String, format: "date-time" })
  generated_at!: string;

  @ApiProperty({ type: () => OpsRecoveryActionResponseDto })
  action!: OpsRecoveryActionResponseDto;

  @ApiProperty({ type: String })
  actor!: string;

  @ApiProperty({ type: String })
  reason!: string;

  @ApiProperty({ type: () => OpsRecoveryFactSnapshotDto })
  source_facts!: OpsRecoveryFactSnapshotDto;

  @ApiProperty({ type: () => OpsRecoveryActionPreviewResponseDto })
  preview!: OpsRecoveryActionPreviewResponseDto;

  @ApiProperty({ type: () => OpsRecoveryFactSnapshotDto })
  before_facts!: OpsRecoveryFactSnapshotDto;

  @ApiPropertyOptional({ type: () => OpsRecoveryFactSnapshotDto, nullable: true })
  after_facts!: OpsRecoveryFactSnapshotDto | null;

  @ApiProperty({ type: () => OpsRecoveryStatusTimelineEntryDto, isArray: true })
  status_timeline!: OpsRecoveryStatusTimelineEntryDto[];

  @ApiProperty({ type: String, isArray: true })
  manual_follow_up!: string[];
}

export class OpsRollbackPlanQueryDto implements OpsRollbackPlanQuery {
  @ApiProperty({ type: String, format: "uuid" })
  @IsUUID()
  deployment_record_id!: string;

  @ApiPropertyOptional({ type: String, enum: OPS_TREND_WINDOW_VALUES, default: "24h" })
  @IsOptional()
  @IsIn(OPS_TREND_WINDOW_VALUES)
  window?: OpsTrendWindow;
}

export class OpsRollbackPlanResponseDto implements OpsRollbackPlanResponse {
  @ApiProperty({ type: String, format: "date-time" })
  generated_at!: string;

  @ApiProperty({ type: String })
  deployment_record_id!: string;

  @ApiProperty({ type: () => OpsReplayRefDto })
  compare_ref!: OpsReplayRefDto;

  @ApiProperty({ type: () => OpsDiagnosticSampleDto, isArray: true })
  affected_samples!: OpsDiagnosticSampleDto[];

  @ApiProperty({ type: () => OpsDeploymentCompareDeltaSummaryDto })
  quality_delta_summary!: OpsDeploymentCompareDeltaSummaryDto;

  @ApiProperty({ type: String })
  smoke_summary!: string;

  @ApiProperty({ type: String, enum: OPS_ROLLBACK_PLAN_CONFIDENCE_VALUES })
  confidence!: OpsRollbackPlanConfidence;

  @ApiProperty({ type: String, isArray: true })
  missing_evidence!: string[];

  @ApiProperty({ type: String, isArray: true })
  manual_checklist!: string[];
}
