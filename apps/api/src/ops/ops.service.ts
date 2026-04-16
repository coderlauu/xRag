import { Injectable } from "@nestjs/common";
import { desc, eq, sql, type SQL } from "drizzle-orm";
import type {
  IncidentSeverity,
  IncidentSource,
  IncidentStatus,
  LatestDeploymentResponse,
  OpsAnswerSummaryResponse,
  OpsEvaluationQualitySummary,
  OpsGovernanceNotice,
  OpsHealthSummaryResponse,
  OpsIncidentCluster,
  OpsIncidentListResponse,
  OpsIncidentSummaryBlock,
  OpsOverviewResponse,
  OpsReadinessBlockingReason,
  OpsReadinessSnapshot,
  OpsRecommendedAction,
  OpsRecommendedActionCode,
  OpsReleaseGuard,
  OpsServiceStatus,
  OpsRuntimeQualitySummary,
  OpsTrendMetric,
  OpsTrendSeries,
  OpsTrendsResponse,
  OpsTrendWindow
} from "@xrag/shared-types";
import { loadApiEnv } from "../config/env";
import { DatabaseService } from "../database/database.service";
import { answerCitations, answerSessions, deploymentRecords, documents, evaluationRuns } from "../database/schema";
import { JobsRepository } from "../jobs/jobs.repository";
import { QueueService } from "../queue/queue.service";
import { StorageService } from "../storage/storage.service";
import { UploadsRepository } from "../uploads/uploads.repository";

type OpsIncidentCandidate = OpsIncidentListResponse["items"][number] & {
  occurredAt: Date;
};

type OpsWindowConfig = {
  window: OpsTrendWindow;
  startAt: Date;
  endAt: Date;
  granularity: OpsTrendSeries["granularity"];
};

type RuntimeTrendBucket = {
  bucket: Date | string;
  terminalCount: number;
  answeredCount: number;
  refusedCount: number;
  latencyP95Ms: number | null;
  avgTokenCostUsd: string | null;
  citedAnsweredSessionCount?: number;
};

type EvaluationTrendBucket = {
  bucket: Date | string;
  groundedness: string | number | null;
  refusalPrecision: string | number | null;
  recallAt10: string | number | null;
  mrr: string | number | null;
  hitInAnswerRate: string | number | null;
  embeddingBacklog: string | number | null;
  freshnessLagP95Ms: string | number | null;
};

@Injectable()
export class OpsService {
  private readonly env = loadApiEnv();

  constructor(
    private readonly database: DatabaseService,
    private readonly jobsRepository: JobsRepository,
    private readonly queueService: QueueService,
    private readonly storageService: StorageService,
    private readonly uploadsRepository: UploadsRepository
  ) {}

  async getHealthSummary(): Promise<OpsHealthSummaryResponse> {
    const recentJobIncidents = await this.jobsRepository.listRecentIncidentCandidates(24);
    const recentUploadIncidents = await this.uploadsRepository.listRecentFailedUploads(12);
    const runtimeSummary = this.summarizeRuntimeHealth(recentJobIncidents, recentUploadIncidents);

    const serviceChecks = await Promise.all([
      this.toHealthItem("api", Promise.resolve("ready")),
      this.toHealthItem("worker", this.queueService.checkConnection().then(() => "queue reachable")),
      this.toHealthItem("storage", this.storageService.checkConnection().then(() => "reachable")),
      this.toHealthItem("database", this.database.checkConnection().then(() => "reachable"))
    ]);

    return {
      services: [...serviceChecks, ...runtimeSummary],
      generated_at: new Date().toISOString()
    };
  }

  async listIncidents(): Promise<OpsIncidentListResponse> {
    const items = await this.listIncidentCandidates();

    return {
      items: items.map(({ occurredAt: _occurredAt, ...item }) => item)
    };
  }

  async getAnswerSummary(): Promise<OpsAnswerSummaryResponse> {
    const [documentSummary] = await this.database.db
      .select({
        embeddingBacklog: sql<number>`count(*) filter (where ${documents.indexStatus} in ('queued', 'chunking', 'embedding'))::int`,
        readyDocumentCount: sql<number>`count(*) filter (where ${documents.indexStatus} = 'ready')::int`,
        staleDocumentCount: sql<number>`count(*) filter (where ${documents.indexStatus} = 'stale')::int`,
        failedDocumentCount: sql<number>`count(*) filter (where ${documents.indexStatus} = 'failed')::int`
      })
      .from(documents);

    const [answerSummary] = await this.database.db
      .select({
        terminalCount: sql<number>`count(*) filter (where ${answerSessions.status} in ('answered', 'needs_scope', 'refused'))::int`,
        answeredCount: sql<number>`count(*) filter (where ${answerSessions.status} = 'answered')::int`,
        refusedCount: sql<number>`count(*) filter (where ${answerSessions.status} = 'refused')::int`,
        answerLatencyP95: sql<number | null>`percentile_cont(0.95) within group (order by ${answerSessions.latencyMs}) filter (where ${answerSessions.status} in ('answered', 'needs_scope', 'refused'))`,
        avgTokenCostUsd: sql<string | null>`to_char(avg(${answerSessions.totalCostUsd}) filter (where ${answerSessions.status} in ('answered', 'needs_scope', 'refused')), 'FM999999990.0000')`
      })
      .from(answerSessions);

    const [citationSummary] = await this.database.db
      .select({
        citedAnsweredSessionCount: sql<number>`count(distinct ${answerCitations.sessionId})::int`
      })
      .from(answerCitations)
      .innerJoin(answerSessions, eq(answerSessions.id, answerCitations.sessionId))
      .where(sql`${answerSessions.status} = 'answered'`);

    const terminalCount = answerSummary?.terminalCount ?? 0;
    const answeredCount = answerSummary?.answeredCount ?? 0;
    const refusedCount = answerSummary?.refusedCount ?? 0;
    const citedAnsweredSessionCount = citationSummary?.citedAnsweredSessionCount ?? 0;

    return {
      embedding_backlog: documentSummary?.embeddingBacklog ?? 0,
      ready_document_count: documentSummary?.readyDocumentCount ?? 0,
      stale_document_count: documentSummary?.staleDocumentCount ?? 0,
      failed_document_count: documentSummary?.failedDocumentCount ?? 0,
      answer_latency_p95: answerSummary?.answerLatencyP95 ?? null,
      citation_coverage: answeredCount > 0 ? Number((citedAnsweredSessionCount / answeredCount).toFixed(4)) : null,
      refusal_rate: terminalCount > 0 ? Number((refusedCount / terminalCount).toFixed(4)) : null,
      avg_token_cost_usd: answerSummary?.avgTokenCostUsd?.trim() ? answerSummary.avgTokenCostUsd.trim() : null
    };
  }

  async getOverview(): Promise<OpsOverviewResponse> {
    const now = new Date();
    const [readiness, runtimeQuality, evaluationQuality, incidents] = await Promise.all([
      this.getReadinessSnapshot(),
      this.getRuntimeQualitySummary("24h", now),
      this.getEvaluationQualitySummary(),
      this.listIncidentCandidates()
    ]);
    const incidentSummary = this.getIncidentSummaryBlock(incidents);
    const releaseGuard = await this.getReleaseGuard(incidentSummary.high_risk_count, evaluationQuality);

    return {
      generated_at: now.toISOString(),
      readiness,
      runtime_quality: runtimeQuality,
      evaluation_quality: evaluationQuality,
      incident_summary: incidentSummary,
      release_guard: releaseGuard,
      recommended_actions: this.getRecommendedActions(readiness, incidentSummary, releaseGuard),
      notices: this.getGovernanceNotices(readiness, releaseGuard)
    };
  }

  async getTrends(window: OpsTrendWindow | undefined): Promise<OpsTrendsResponse> {
    const config = this.getWindowConfig(window);
    const [runtimeSeries, evaluationSeries] = await Promise.all([
      this.getRuntimeTrendSeries(config),
      this.getEvaluationTrendSeries(config)
    ]);

    return {
      window: config.window,
      generated_at: config.endAt.toISOString(),
      series: [...runtimeSeries, ...evaluationSeries]
    };
  }

  async getLatestDeployment(): Promise<LatestDeploymentResponse> {
    return {
      current_image_tag: process.env.XRAG_IMAGE_TAG || null,
      previous_stable_image_tag: process.env.XRAG_PREVIOUS_IMAGE_TAG || null,
      last_smoke_status: (process.env.XRAG_LAST_SMOKE_STATUS as LatestDeploymentResponse["last_smoke_status"]) || "unknown",
      last_smoke_at: process.env.XRAG_LAST_SMOKE_AT || null
    };
  }

  private async getReadinessSnapshot(): Promise<OpsReadinessSnapshot> {
    const [summary] = await this.database.db
      .select({
        queuedCount: sql<number>`count(*) filter (where ${documents.indexStatus} = 'queued')::int`,
        chunkingCount: sql<number>`count(*) filter (where ${documents.indexStatus} = 'chunking')::int`,
        embeddingCount: sql<number>`count(*) filter (where ${documents.indexStatus} = 'embedding')::int`,
        readyCount: sql<number>`count(*) filter (where ${documents.indexStatus} = 'ready' and ${documents.citationReady} = true)::int`,
        staleCount: sql<number>`count(*) filter (where ${documents.indexStatus} = 'stale')::int`,
        failedCount: sql<number>`count(*) filter (where ${documents.indexStatus} = 'failed')::int`,
        totalCount: sql<number>`count(*)::int`,
        freshnessLagP95Ms: sql<number | null>`
          percentile_cont(0.95) within group (
            order by extract(epoch from (${documents.indexedAt} - ${documents.importedAt})) * 1000
          ) filter (
            where ${documents.indexedAt} is not null
              and ${documents.indexStatus} in ('ready', 'stale')
          )
        `
      })
      .from(documents);

    const queuedCount = summary?.queuedCount ?? 0;
    const chunkingCount = summary?.chunkingCount ?? 0;
    const embeddingCount = summary?.embeddingCount ?? 0;
    const readyCount = summary?.readyCount ?? 0;
    const staleCount = summary?.staleCount ?? 0;
    const failedCount = summary?.failedCount ?? 0;
    const totalCount = summary?.totalCount ?? 0;
    const backlogCount = queuedCount + chunkingCount + embeddingCount;

    return {
      queued_count: queuedCount,
      chunking_count: chunkingCount,
      embedding_count: embeddingCount,
      ready_count: readyCount,
      stale_count: staleCount,
      failed_count: failedCount,
      total_count: totalCount,
      readiness_rate: totalCount > 0 ? Number((readyCount / totalCount).toFixed(4)) : null,
      freshness_lag_p95_ms: this.toNullableNumber(summary?.freshnessLagP95Ms ?? null),
      blocking_reason: this.getReadinessBlockingReason({
        readyCount,
        backlogCount,
        failedCount,
        staleCount
      })
    };
  }

  private async getRuntimeQualitySummary(window: OpsTrendWindow, now: Date): Promise<OpsRuntimeQualitySummary> {
    const config = this.getWindowConfig(window, now);
    const answerEventAtExpr = this.getAnswerEventAtExpression();
    const [answerSummary] = await this.database.db
      .select({
        terminalCount: sql<number>`count(*) filter (where ${answerSessions.status} in ('answered', 'needs_scope', 'refused'))::int`,
        answeredCount: sql<number>`count(*) filter (where ${answerSessions.status} = 'answered')::int`,
        refusedCount: sql<number>`count(*) filter (where ${answerSessions.status} = 'refused')::int`,
        latencyP50Ms: sql<number | null>`percentile_cont(0.50) within group (order by ${answerSessions.latencyMs}) filter (where ${answerSessions.status} in ('answered', 'needs_scope', 'refused'))`,
        latencyP95Ms: sql<number | null>`percentile_cont(0.95) within group (order by ${answerSessions.latencyMs}) filter (where ${answerSessions.status} in ('answered', 'needs_scope', 'refused'))`,
        avgTokenCostUsd: sql<string | null>`to_char(avg(${answerSessions.totalCostUsd}) filter (where ${answerSessions.status} in ('answered', 'needs_scope', 'refused')), 'FM999999990.0000')`
      })
      .from(answerSessions)
      .where(sql`${answerEventAtExpr} >= ${config.startAt} and ${answerEventAtExpr} <= ${config.endAt}`);

    const [citationSummary] = await this.database.db
      .select({
        answeredCount: sql<number>`count(distinct ${answerSessions.id})::int`,
        citedAnsweredSessionCount: sql<number>`count(distinct ${answerSessions.id}) filter (where ${answerCitations.id} is not null)::int`
      })
      .from(answerSessions)
      .leftJoin(answerCitations, eq(answerSessions.id, answerCitations.sessionId))
      .where(
        sql`${answerSessions.status} = 'answered' and ${answerEventAtExpr} >= ${config.startAt} and ${answerEventAtExpr} <= ${config.endAt}`
      );

    const terminalCount = answerSummary?.terminalCount ?? 0;
    const answeredCount = citationSummary?.answeredCount ?? answerSummary?.answeredCount ?? 0;
    const refusedCount = answerSummary?.refusedCount ?? 0;
    const citedAnsweredSessionCount = citationSummary?.citedAnsweredSessionCount ?? 0;

    return {
      window,
      terminal_session_count: terminalCount,
      answered_session_count: answeredCount,
      latency_p50_ms: answerSummary?.latencyP50Ms ?? null,
      latency_p95_ms: answerSummary?.latencyP95Ms ?? null,
      citation_coverage: answeredCount > 0 ? Number((citedAnsweredSessionCount / answeredCount).toFixed(4)) : null,
      refusal_rate: terminalCount > 0 ? Number((refusedCount / terminalCount).toFixed(4)) : null,
      avg_token_cost_usd: answerSummary?.avgTokenCostUsd?.trim() ? answerSummary.avgTokenCostUsd.trim() : null
    };
  }

  private async getEvaluationQualitySummary(): Promise<OpsEvaluationQualitySummary | null> {
    const [latestRun] = await this.database.db
      .select()
      .from(evaluationRuns)
      .where(sql`${evaluationRuns.status} in ('completed', 'failed')`)
      .orderBy(desc(evaluationRuns.completedAt), desc(evaluationRuns.createdAt))
      .limit(1);

    if (!latestRun || (latestRun.status !== "completed" && latestRun.status !== "failed")) {
      return null;
    }

    return {
      latest_run_ref: latestRun.runRef,
      environment: latestRun.environment,
      source: latestRun.source,
      status: latestRun.status,
      commit_sha: latestRun.commitSha,
      dataset_version: latestRun.datasetVersion,
      completed_at: latestRun.completedAt?.toISOString() ?? null,
      recall_at_10: this.toNullableNumber(latestRun.recallAt10),
      mrr: this.toNullableNumber(latestRun.mrr),
      hit_in_answer_rate: this.toNullableNumber(latestRun.hitInAnswerRate),
      groundedness: this.toNullableNumber(latestRun.groundedness),
      citation_coverage: this.toNullableNumber(latestRun.citationCoverage),
      refusal_precision: this.toNullableNumber(latestRun.refusalPrecision),
      latency_p95_ms: latestRun.latencyP95Ms,
      avg_token_cost_usd: latestRun.avgTokenCostUsd
    };
  }

  private async getReleaseGuard(
    relatedIncidentCount: number,
    evaluationQuality: OpsEvaluationQualitySummary | null
  ): Promise<OpsReleaseGuard> {
    const [latestRecord] = await this.database.db
      .select()
      .from(deploymentRecords)
      .orderBy(desc(deploymentRecords.deployedAt), desc(deploymentRecords.createdAt))
      .limit(1);

    if (latestRecord) {
      const relatedEvaluationRunRef =
        latestRecord.commitSha && evaluationQuality?.commit_sha === latestRecord.commitSha
          ? evaluationQuality.latest_run_ref
          : await this.getEvaluationRunRefByCommitSha(latestRecord.commitSha);

      return {
        risk_level: this.getReleaseGuardRiskLevel(latestRecord.smokeStatus, relatedIncidentCount),
        current_image_tag: latestRecord.currentImageTag,
        previous_stable_image_tag: latestRecord.previousStableImageTag,
        smoke_status: latestRecord.smokeStatus,
        smoke_at: latestRecord.smokeAt?.toISOString() ?? null,
        deployed_at: latestRecord.deployedAt.toISOString(),
        workflow_run_id: latestRecord.workflowRunId,
        related_evaluation_run_ref: relatedEvaluationRunRef,
        related_incident_count: relatedIncidentCount,
        summary: this.getReleaseGuardSummary(latestRecord.smokeStatus, relatedIncidentCount)
      };
    }

    const latestDeployment = await this.getLatestDeployment();

    return {
      risk_level: this.getReleaseGuardRiskLevel(latestDeployment.last_smoke_status, relatedIncidentCount),
      current_image_tag: latestDeployment.current_image_tag,
      previous_stable_image_tag: latestDeployment.previous_stable_image_tag,
      smoke_status: latestDeployment.last_smoke_status,
      smoke_at: latestDeployment.last_smoke_at,
      deployed_at: null,
      workflow_run_id: null,
      related_evaluation_run_ref: null,
      related_incident_count: relatedIncidentCount,
      summary: latestDeployment.current_image_tag
        ? this.getReleaseGuardSummary(latestDeployment.last_smoke_status, relatedIncidentCount)
        : "尚未记录 deployment_records，release guard 只能使用环境变量兜底。"
    };
  }

  private getReadinessBlockingReason(input: {
    readyCount: number;
    backlogCount: number;
    failedCount: number;
    staleCount: number;
  }): OpsReadinessBlockingReason {
    if (input.readyCount === 0) {
      return "no_ready_documents";
    }

    if (input.failedCount > 0) {
      return "indexing_failed";
    }

    if (input.backlogCount > 0) {
      return "indexing_backlog";
    }

    if (input.staleCount > 0) {
      return "stale_corpus";
    }

    return "none";
  }

  private getIncidentSummaryBlock(incidents: OpsIncidentCandidate[]): OpsIncidentSummaryBlock {
    const clusters = new Map<string, OpsIncidentCluster>();

    for (const incident of incidents) {
      const key = `${incident.source}:${incident.severity}:${incident.status}`;
      const existing = clusters.get(key);

      if (existing) {
        existing.incident_count += 1;
        existing.latest_incident_ref = existing.latest_incident_ref || incident.incident_ref;
        continue;
      }

      clusters.set(key, {
        cluster_key: key,
        source: incident.source,
        severity: incident.severity,
        status: incident.status,
        incident_count: 1,
        latest_incident_ref: incident.incident_ref,
        affected_surface: this.getAffectedSurface(incident.source),
        recommended_action_code: this.getIncidentRecommendedActionCode(incident.source)
      });
    }

    return {
      open_count: incidents.filter((incident) => incident.status !== "resolved").length,
      high_risk_count: incidents.filter((incident) => incident.severity === "high").length,
      clusters: Array.from(clusters.values()).sort((left, right) => {
        return right.incident_count - left.incident_count || left.cluster_key.localeCompare(right.cluster_key);
      })
    };
  }

  private getRecommendedActions(
    readiness: OpsReadinessSnapshot,
    incidentSummary: OpsIncidentSummaryBlock,
    releaseGuard: OpsReleaseGuard
  ): OpsRecommendedAction[] {
    const actions: OpsRecommendedAction[] = [];

    if (readiness.blocking_reason !== "none") {
      actions.push({
        code: this.getReadinessActionCode(readiness.blocking_reason),
        priority: readiness.blocking_reason === "indexing_failed" || readiness.blocking_reason === "no_ready_documents"
          ? "high"
          : "medium",
        surface: "indexing",
        title: "检查语料就绪状态",
        summary: "当前语料就绪状态可能影响 Ask 可信回答，先检查索引积压、失败和 stale 文档。"
      });
    }

    if (incidentSummary.high_risk_count > 0) {
      actions.push({
        code: "inspect_incident_cluster",
        priority: "high",
        surface: "ops",
        title: "检查高风险 incident cluster",
        summary: `当前有 ${incidentSummary.high_risk_count} 条高风险 incident，优先按来源聚类排查。`
      });
    }

    if (releaseGuard.risk_level === "critical") {
      actions.push({
        code: "rollback_to_previous_stable",
        priority: "high",
        surface: "deployment",
        title: "核对上一稳定版本",
        summary: "release guard 处于 critical，需要人工核对 smoke、incident 和上一稳定镜像后再决定是否回滚。"
      });
    } else if (releaseGuard.risk_level === "warning") {
      actions.push({
        code: "verify_latest_deployment",
        priority: "medium",
        surface: "deployment",
        title: "核对最近部署事实",
        summary: "当前 release guard 仍有观察风险，先核对最近部署、smoke 和关联质量运行结果。"
      });
    }

    if (actions.length === 0) {
      actions.push({
        code: "monitor_without_action",
        priority: "low",
        surface: "ops",
        title: "继续观察",
        summary: "当前没有必须立即处理的治理动作，保持常规巡检即可。"
      });
    }

    return actions;
  }

  private getGovernanceNotices(readiness: OpsReadinessSnapshot, releaseGuard: OpsReleaseGuard): OpsGovernanceNotice[] {
    const notices: OpsGovernanceNotice[] = [];

    if (readiness.blocking_reason !== "none") {
      notices.push(
        {
          target: "ask",
          level: readiness.blocking_reason === "no_ready_documents" ? "critical" : "warning",
          code: readiness.blocking_reason,
          title: "语料就绪状态需要关注",
          summary: "当前索引或引用就绪状态可能影响回答证据，请先查看运维主板。"
        },
        {
          target: "search",
          level: "warning",
          code: readiness.blocking_reason,
          title: "索引状态需要关注",
          summary: "搜索结果可能受索引积压、失败或 stale 文档影响。"
        },
        {
          target: "detail",
          level: "warning",
          code: readiness.blocking_reason,
          title: "文档引用状态需要关注",
          summary: "文档详情中的 citation readiness 可能需要重新索引或人工排查。"
        }
      );
    }

    if (releaseGuard.risk_level === "critical") {
      notices.push({
        target: "ask",
        level: "critical",
        code: "rollback_to_previous_stable",
        title: "发布风险需要关注",
        summary: "最近发布或 smoke 状态存在高风险，继续问答前建议查看 release guard。"
      });
    }

    return notices;
  }

  private normalizeTrendWindow(window: OpsTrendWindow | undefined): OpsTrendWindow {
    if (window === "24h" || window === "7d" || window === "30d") {
      return window;
    }

    return "7d";
  }

  private getAffectedSurface(source: IncidentSource): OpsIncidentCluster["affected_surface"] {
    switch (source) {
      case "upload":
        return "upload";
      case "ocr":
      case "parse":
      case "fetch":
      case "projection":
        return "indexing";
      case "deploy":
        return "deployment";
      case "ci":
        return "ci";
    }
  }

  private getIncidentRecommendedActionCode(source: IncidentSource): OpsRecommendedActionCode {
    switch (source) {
      case "upload":
      case "parse":
      case "ocr":
      case "fetch":
      case "projection":
        return "inspect_incident_cluster";
      case "deploy":
      case "ci":
        return "verify_latest_deployment";
    }
  }

  private getReadinessActionCode(reason: OpsReadinessBlockingReason): OpsRecommendedActionCode {
    switch (reason) {
      case "indexing_backlog":
        return "inspect_indexing_backlog";
      case "indexing_failed":
        return "inspect_failed_documents";
      case "no_ready_documents":
        return "run_backfill_indexing_dry_run";
      case "stale_corpus":
        return "inspect_quality_regression";
      case "none":
        return "monitor_without_action";
    }
  }

  private getReleaseGuardRiskLevel(smokeStatus: LatestDeploymentResponse["last_smoke_status"], relatedIncidentCount: number) {
    if (smokeStatus === "failed") {
      return "critical";
    }

    if (smokeStatus === "unknown" || relatedIncidentCount > 0) {
      return "warning";
    }

    return "healthy";
  }

  private getReleaseGuardSummary(
    smokeStatus: LatestDeploymentResponse["last_smoke_status"],
    relatedIncidentCount: number
  ) {
    if (smokeStatus === "failed") {
      return "最近 smoke 失败，需要人工核对并评估是否回滚。";
    }

    if (relatedIncidentCount > 0) {
      return "存在高风险 incident，需要结合最近部署记录判断是否发布相关。";
    }

    if (smokeStatus === "unknown") {
      return "尚未记录稳定 smoke 结果，需要继续观察。";
    }

    return "最近部署与 smoke 未显示明显风险。";
  }

  private toNullableNumber(value: string | number | null): number | null {
    if (value === null) {
      return null;
    }

    return Number(value);
  }

  private getWindowConfig(window: OpsTrendWindow | undefined, now = new Date()): OpsWindowConfig {
    const normalized = this.normalizeTrendWindow(window);
    const endAt = new Date(now);
    const startAt = new Date(now);

    switch (normalized) {
      case "24h":
        startAt.setHours(startAt.getHours() - 24);
        return { window: normalized, startAt, endAt, granularity: "hour" };
      case "30d":
        startAt.setDate(startAt.getDate() - 30);
        return { window: normalized, startAt, endAt, granularity: "day" };
      case "7d":
      default:
        startAt.setDate(startAt.getDate() - 7);
        return { window: "7d", startAt, endAt, granularity: "day" };
    }
  }

  private getAnswerEventAtExpression(): SQL {
    return sql`coalesce(${answerSessions.finishedAt}, ${answerSessions.updatedAt}, ${answerSessions.createdAt})`;
  }

  private getEvaluationEventAtExpression(): SQL {
    return sql`coalesce(${evaluationRuns.completedAt}, ${evaluationRuns.createdAt})`;
  }

  private getBucketExpression(timestampExpression: SQL, granularity: OpsTrendSeries["granularity"]) {
    if (granularity === "hour") {
      return sql<Date>`date_trunc('hour', ${timestampExpression})`;
    }

    return sql<Date>`date_trunc('day', ${timestampExpression})`;
  }

  private async getRuntimeTrendSeries(config: OpsWindowConfig): Promise<OpsTrendSeries[]> {
    const answerEventAtExpr = this.getAnswerEventAtExpression();
    const bucketExpr = this.getBucketExpression(answerEventAtExpr, config.granularity);

    const [runtimeRows, citationRows] = await Promise.all([
      this.database.db
        .select({
          bucket: bucketExpr,
          terminalCount: sql<number>`count(*) filter (where ${answerSessions.status} in ('answered', 'needs_scope', 'refused'))::int`,
          answeredCount: sql<number>`count(*) filter (where ${answerSessions.status} = 'answered')::int`,
          refusedCount: sql<number>`count(*) filter (where ${answerSessions.status} = 'refused')::int`,
          latencyP95Ms: sql<number | null>`percentile_cont(0.95) within group (order by ${answerSessions.latencyMs}) filter (where ${answerSessions.status} in ('answered', 'needs_scope', 'refused'))`,
          avgTokenCostUsd: sql<string | null>`to_char(avg(${answerSessions.totalCostUsd}) filter (where ${answerSessions.status} in ('answered', 'needs_scope', 'refused')), 'FM999999990.0000')`
        })
        .from(answerSessions)
        .where(sql`${answerEventAtExpr} >= ${config.startAt} and ${answerEventAtExpr} <= ${config.endAt}`)
        .groupBy(bucketExpr)
        .orderBy(bucketExpr),
      this.database.db
        .select({
          bucket: bucketExpr,
          answeredCount: sql<number>`count(distinct ${answerSessions.id})::int`,
          citedAnsweredSessionCount: sql<number>`count(distinct ${answerSessions.id}) filter (where ${answerCitations.id} is not null)::int`
        })
        .from(answerSessions)
        .leftJoin(answerCitations, eq(answerSessions.id, answerCitations.sessionId))
        .where(
          sql`${answerSessions.status} = 'answered' and ${answerEventAtExpr} >= ${config.startAt} and ${answerEventAtExpr} <= ${config.endAt}`
        )
        .groupBy(bucketExpr)
        .orderBy(bucketExpr)
    ]);

    const buckets = new Map<string, RuntimeTrendBucket>();

    for (const row of runtimeRows) {
      buckets.set(this.toBucketTimestamp(row.bucket), {
        bucket: row.bucket,
        terminalCount: row.terminalCount ?? 0,
        answeredCount: row.answeredCount ?? 0,
        refusedCount: row.refusedCount ?? 0,
        latencyP95Ms: row.latencyP95Ms ?? null,
        avgTokenCostUsd: row.avgTokenCostUsd?.trim() ? row.avgTokenCostUsd.trim() : null
      });
    }

    for (const row of citationRows) {
      const key = this.toBucketTimestamp(row.bucket);
      const existing = buckets.get(key);

      if (existing) {
        existing.citedAnsweredSessionCount = row.citedAnsweredSessionCount ?? 0;
        existing.answeredCount = row.answeredCount ?? existing.answeredCount;
      } else {
        buckets.set(key, {
          bucket: row.bucket,
          terminalCount: 0,
          answeredCount: row.answeredCount ?? 0,
          refusedCount: 0,
          latencyP95Ms: null,
          avgTokenCostUsd: null,
          citedAnsweredSessionCount: row.citedAnsweredSessionCount ?? 0
        });
      }
    }

    const rows = Array.from(buckets.values()).sort((left, right) => {
      return this.toBucketTimestamp(left.bucket).localeCompare(this.toBucketTimestamp(right.bucket));
    });

    return [
      this.buildTrendSeries("citation_coverage", "runtime", config.granularity, rows, (row) =>
        row.answeredCount > 0
          ? Number((((row.citedAnsweredSessionCount ?? 0) / row.answeredCount).toFixed(4)))
          : null
      ),
      this.buildTrendSeries("refusal_rate", "runtime", config.granularity, rows, (row) =>
        row.terminalCount > 0 ? Number((row.refusedCount / row.terminalCount).toFixed(4)) : null
      ),
      this.buildTrendSeries("latency_p95_ms", "runtime", config.granularity, rows, (row) =>
        this.toNullableNumber(row.latencyP95Ms)
      ),
      this.buildTrendSeries("avg_token_cost_usd", "runtime", config.granularity, rows, (row) => row.avgTokenCostUsd)
    ].filter((series): series is OpsTrendSeries => series !== null);
  }

  private async getEvaluationTrendSeries(config: OpsWindowConfig): Promise<OpsTrendSeries[]> {
    const evaluationEventAtExpr = this.getEvaluationEventAtExpression();
    const bucketExpr = this.getBucketExpression(evaluationEventAtExpr, config.granularity);

    const rows = await this.database.db
      .select({
        bucket: bucketExpr,
        groundedness: sql<string | null>`avg(${evaluationRuns.groundedness})::text`,
        refusalPrecision: sql<string | null>`avg(${evaluationRuns.refusalPrecision})::text`,
        recallAt10: sql<string | null>`avg(${evaluationRuns.recallAt10})::text`,
        mrr: sql<string | null>`avg(${evaluationRuns.mrr})::text`,
        hitInAnswerRate: sql<string | null>`avg(${evaluationRuns.hitInAnswerRate})::text`,
        embeddingBacklog: sql<string | null>`avg(${evaluationRuns.embeddingBacklog})::text`,
        freshnessLagP95Ms: sql<string | null>`avg(${evaluationRuns.freshnessLagP95Ms})::text`
      })
      .from(evaluationRuns)
      .where(
        sql`${evaluationRuns.status} in ('completed', 'failed') and ${evaluationEventAtExpr} >= ${config.startAt} and ${evaluationEventAtExpr} <= ${config.endAt}`
      )
      .groupBy(bucketExpr)
      .orderBy(bucketExpr);

    const normalizedRows: EvaluationTrendBucket[] = rows.map((row) => ({
      bucket: row.bucket,
      groundedness: row.groundedness,
      refusalPrecision: row.refusalPrecision,
      recallAt10: row.recallAt10,
      mrr: row.mrr,
      hitInAnswerRate: row.hitInAnswerRate,
      embeddingBacklog: row.embeddingBacklog,
      freshnessLagP95Ms: row.freshnessLagP95Ms
    }));

    return [
      this.buildTrendSeries("groundedness", "evaluation", config.granularity, normalizedRows, (row) =>
        this.toNullableNumber(row.groundedness)
      ),
      this.buildTrendSeries("refusal_precision", "evaluation", config.granularity, normalizedRows, (row) =>
        this.toNullableNumber(row.refusalPrecision)
      ),
      this.buildTrendSeries("recall_at_10", "evaluation", config.granularity, normalizedRows, (row) =>
        this.toNullableNumber(row.recallAt10)
      ),
      this.buildTrendSeries("mrr", "evaluation", config.granularity, normalizedRows, (row) =>
        this.toNullableNumber(row.mrr)
      ),
      this.buildTrendSeries("hit_in_answer_rate", "evaluation", config.granularity, normalizedRows, (row) =>
        this.toNullableNumber(row.hitInAnswerRate)
      ),
      this.buildTrendSeries("embedding_backlog", "evaluation", config.granularity, normalizedRows, (row) =>
        this.toNullableNumber(row.embeddingBacklog)
      ),
      this.buildTrendSeries("freshness_lag_p95_ms", "evaluation", config.granularity, normalizedRows, (row) =>
        this.toNullableNumber(row.freshnessLagP95Ms)
      )
    ].filter((series): series is OpsTrendSeries => series !== null);
  }

  private buildTrendSeries<TBucket extends { bucket: Date | string }>(
    metric: OpsTrendMetric,
    source: OpsTrendSeries["source"],
    granularity: OpsTrendSeries["granularity"],
    rows: TBucket[],
    getValue: (row: TBucket) => number | string | null
  ): OpsTrendSeries | null {
    const points = rows.map((row) => ({
      ts: this.toBucketTimestamp(row.bucket),
      value: getValue(row)
    }));

    if (!points.some((point) => point.value !== null)) {
      return null;
    }

    return {
      metric,
      source,
      granularity,
      points
    };
  }

  private toBucketTimestamp(value: Date | string): string {
    const date = value instanceof Date ? value : new Date(value);
    return date.toISOString();
  }

  private async getEvaluationRunRefByCommitSha(commitSha: string | null): Promise<string | null> {
    if (!commitSha) {
      return null;
    }

    const [run] = await this.database.db
      .select({
        runRef: evaluationRuns.runRef
      })
      .from(evaluationRuns)
      .where(sql`${evaluationRuns.commitSha} = ${commitSha} and ${evaluationRuns.status} in ('completed', 'failed')`)
      .orderBy(desc(evaluationRuns.completedAt), desc(evaluationRuns.createdAt))
      .limit(1);

    return run?.runRef ?? null;
  }

  private async listIncidentCandidates(): Promise<OpsIncidentCandidate[]> {
    const [jobIncidents, uploadIncidents] = await Promise.all([
      this.jobsRepository.listRecentIncidentCandidates(12),
      this.uploadsRepository.listRecentFailedUploads(8)
    ]);

    return [
      ...jobIncidents.map((job) => {
        const source = this.resolveIncidentSource(job.jobType);
        const title = this.getJobIncidentTitle(job.diagnosisCode, job.jobType);
        const summary = job.errorMessage || this.getDefaultIncidentSummary(source);

        return {
          incident_ref: job.incidentRef || this.buildIncidentRef("JOB", job.id),
          source: source as IncidentSource,
          severity: this.getIncidentSeverity(job.diagnosisCode, source),
          status: this.getIncidentStatus(job.status),
          title,
          summary,
          external_url: null,
          occurredAt: job.finishedAt || job.createdAt
        };
      }),
      ...uploadIncidents.map((upload) => ({
        incident_ref: this.buildIncidentRef("UPL", upload.id),
        source: "upload" as IncidentSource,
        severity: this.getIncidentSeverity(upload.errorCode, "upload"),
        status: "open" as IncidentStatus,
        title: this.getUploadIncidentTitle(upload.errorCode),
        summary: upload.errorMessage || `上传 ${upload.fileName} 失败，请检查对象存储与分片状态。`,
        external_url: null,
        occurredAt: upload.completedAt || upload.createdAt
      }))
    ]
      .sort((left, right) => right.occurredAt.getTime() - left.occurredAt.getTime())
      .slice(0, 20);
  }

  private async toHealthItem(name: string, check: Promise<string>) {
    try {
      const detail = await check;
      return {
        name,
        status: "healthy" as OpsServiceStatus,
        detail
      };
    } catch (error) {
      return {
        name,
        status: "warning" as OpsServiceStatus,
        detail: error instanceof Error ? error.message : "unavailable"
      };
    }
  }

  private getIncidentSeverity(code: string | null, source: IncidentSource): IncidentSeverity {
    if (source === "ci" || source === "projection") {
      return "medium";
    }

    if (
      code === "queue_backlog" ||
      code === "object_missing_on_complete" ||
      code === "pdf_parse_timeout" ||
      code === "pdf_parse_runtime_error" ||
      code === "ocr_timeout" ||
      code === "ocr_runtime_error" ||
      code === "link_fetch_timeout" ||
      code === "link_fetch_blocked"
    ) {
      return "high";
    }

    return "medium";
  }

  private getIncidentStatus(jobStatus: string): IncidentStatus {
    return jobStatus === "dead" ? "tracked" : "open";
  }

  private getJobIncidentTitle(code: string | null, jobType: string): string {
    switch (code) {
      case "pdf_parse_runtime_error":
        return "PDF 解析器运行时异常";
      case "pdf_parse_timeout":
        return "PDF 解析超时";
      case "pdf_parse_unsupported":
        return "PDF 无法解析";
      case "pdf_parse_empty_text":
        return "PDF 提取结果为空";
      case "ocr_runtime_error":
        return "OCR 运行时异常";
      case "ocr_timeout":
        return "OCR 解析超时";
      case "ocr_no_text_detected":
        return "OCR 未识别到有效文本";
      case "link_fetch_timeout":
        return "链接抓取超时";
      case "link_fetch_blocked":
        return "链接抓取被阻止";
      case "link_extract_empty":
        return "链接正文提取为空";
      case "link_invalid_url":
        return "链接地址无效";
      case "search_projection_stale":
        return "搜索投影已过期";
      case "queue_backlog":
        return "解析任务入队失败";
      default:
        if (jobType === "fetch_link") {
          return "链接抓取任务失败";
        }

        if (jobType === "run_ocr") {
          return "OCR 任务失败";
        }

        if (jobType === "rebuild_search_projection" || jobType === "refresh_search_projection") {
          return "搜索投影任务失败";
        }

        return jobType === "reparse_document" ? "重试解析失败" : "解析任务失败";
    }
  }

  private getUploadIncidentTitle(code: string | null): string {
    switch (code) {
      case "multipart_part_failed":
        return "分片上传失败";
      case "upload_complete_invalid_parts":
        return "上传完成校验失败";
      case "object_missing_on_complete":
        return "对象存储缺少上传对象";
      case "storage_presign_failed":
        return "上传签名生成失败";
      default:
        return "上传链路失败";
    }
  }

  private buildIncidentRef(prefix: "JOB" | "UPL", id: string) {
    return `${prefix}-${id.slice(0, 8).toUpperCase()}`;
  }

  private resolveIncidentSource(jobType: string): IncidentSource {
    switch (jobType) {
      case "run_ocr":
        return "ocr";
      case "fetch_link":
        return "fetch";
      case "rebuild_search_projection":
      case "refresh_search_projection":
        return "projection";
      default:
        return "parse";
    }
  }

  private getDefaultIncidentSummary(source: IncidentSource): string {
    switch (source) {
      case "ocr":
        return "OCR 任务失败，请检查 OCR 引擎与输入文件。";
      case "fetch":
        return "链接抓取任务失败，请检查目标地址与抓取策略。";
      case "projection":
        return "搜索投影更新失败，请检查任务与索引状态。";
      default:
        return "解析任务失败，请查看任务错误与文档详情。";
    }
  }

  private summarizeRuntimeHealth(
    jobIncidents: Awaited<ReturnType<JobsRepository["listRecentIncidentCandidates"]>>,
    uploadIncidents: Awaited<ReturnType<UploadsRepository["listRecentFailedUploads"]>>
  ) {
    const ocrFailures = jobIncidents.filter((job) => this.resolveIncidentSource(job.jobType) === "ocr");
    const fetchFailures = jobIncidents.filter((job) => this.resolveIncidentSource(job.jobType) === "fetch");
    const projectionFailures = jobIncidents.filter((job) => this.resolveIncidentSource(job.jobType) === "projection");
    const uploadFailures = uploadIncidents.filter((upload) => upload.errorCode !== null);

    return [
      this.toRuntimeHealthItem("ocr-runtime", ocrFailures, "最近未检测到 OCR 异常。", "最近 OCR 任务失败"),
      this.toRuntimeHealthItem("link-fetcher", fetchFailures, "最近未检测到链接抓取异常。", "最近链接抓取失败"),
      this.toRuntimeHealthItem("search-projection", projectionFailures, "最近未检测到搜索投影异常。", "最近搜索投影失败"),
      this.toRuntimeHealthItem("upload-chain", uploadFailures, "最近未检测到上传链路异常。", "最近上传链路失败")
    ];
  }

  private toRuntimeHealthItem(
    name: string,
    failures: Array<{ diagnosisCode?: string | null; errorCode?: string | null }>,
    healthyDetail: string,
    failedPrefix: string
  ) {
    if (failures.length === 0) {
      return {
        name,
        status: "healthy" as OpsServiceStatus,
        detail: healthyDetail
      };
    }

    const criticalCodes = new Set([
      "queue_backlog",
      "pdf_parse_timeout",
      "pdf_parse_runtime_error",
      "ocr_timeout",
      "ocr_runtime_error",
      "link_fetch_timeout",
      "link_fetch_blocked",
      "search_projection_stale",
      "object_missing_on_complete"
    ]);
    const hasCritical = failures.some((failure) => {
      const code = failure.diagnosisCode ?? failure.errorCode;
      return Boolean(code && criticalCodes.has(code));
    });

    return {
      name,
      status: (hasCritical ? "critical" : "warning") as OpsServiceStatus,
      detail: `${failedPrefix} ${failures.length} 条，请优先检查最近 incident。`
    };
  }
}
