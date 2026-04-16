import { ApiProperty, ApiPropertyOptional } from "@nestjs/swagger";
import type {
  DeploymentSmokeStatus,
  IncidentSeverity,
  IncidentSource,
  IncidentStatus,
  LatestDeploymentResponse,
  OpsAnswerSummaryResponse,
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
  OpsReleaseGuard,
  OpsReleaseGuardRiskLevel,
  OpsServiceStatus,
  OpsRuntimeQualitySummary,
  OpsTrendMetric,
  OpsTrendPoint,
  OpsTrendSeries,
  OpsTrendSource,
  OpsTrendsResponse,
  OpsTrendWindow
} from "@xrag/shared-types";

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
