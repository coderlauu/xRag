import { createHash, randomUUID } from "node:crypto";
import { BadRequestException, HttpException, Injectable, NotFoundException } from "@nestjs/common";
import { and, asc, desc, eq, gte, sql, type SQL } from "drizzle-orm";
import type {
  IncidentSeverity,
  IncidentSource,
  IncidentStatus,
  LatestDeploymentResponse,
  OpsAnswerSessionReplayResponse,
  OpsAnswerSummaryResponse,
  OpsDeploymentCompareQuery,
  OpsDeploymentCompareResponse,
  OpsDiagnosticSampleListQuery,
  OpsDiagnosticSampleListResponse,
  OpsDocumentReplayResponse,
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
  OpsRecoveryActionCreateRequest,
  OpsRecoveryActionAuditResponse,
  OpsRecoveryActionPreviewRequest,
  OpsRecoveryActionPreviewResponse,
  OpsRecoveryActionResponse,
  OpsRecoveryFactSnapshot,
  OpsRecoveryCandidateListQuery,
  OpsRecoveryCandidateListResponse,
  OpsRecoveryCandidateSourceType,
  OpsRollbackPlanQuery,
  OpsRollbackPlanResponse,
  OpsReleaseGuard,
  OpsServiceStatus,
  OpsRuntimeQualitySummary,
  OpsTrendMetric,
  OpsTrendSeries,
  OpsTrendsResponse,
  OpsTrendWindow
} from "@xrag/shared-types";
import { AnswersService } from "../answers/answers.service";
import { loadApiEnv } from "../config/env";
import { DatabaseService } from "../database/database.service";
import {
  answerCitations,
  answerSessions,
  deploymentRecords,
  documentParseJobs,
  documents,
  evaluationRuns,
  operatorRecoveryActions
} from "../database/schema";
import { DocumentsService } from "../documents/documents.service";
import { JobsRepository } from "../jobs/jobs.repository";
import { QueueService } from "../queue/queue.service";
import { StorageService } from "../storage/storage.service";
import { UploadsRepository } from "../uploads/uploads.repository";
import { listDiagnosticSamplesFromDb } from "./ops.diagnostic-samples";
import { buildDeploymentCompareResponse, getDeploymentCompareSamples } from "./ops.deployment-compare";
import {
  buildAnswerSessionReplayResponse,
  buildDocumentReplayResponse,
  countRelatedAnswerSessionsForDocument
} from "./ops.replays";
import {
  buildAnswerRecoveryFactSnapshot,
  buildDocumentRecoveryFactSnapshot,
  buildRecoveryCandidate,
  buildRecoveryPreview,
  filterRecoveryCandidates,
  getAnswerPreviewPreconditions,
  getDocumentPreviewPreconditions
} from "./ops.recovery-candidates";
import {
  buildRecoveryJobRef,
  parseRecoveryCandidateId,
  readRecoveryFactSnapshot,
  toRecoveryActionAuditResponse,
  toRecoveryActionResponse,
  type RecoveryActionRow
} from "./ops.recovery-actions";

const WORKER_STALL_WARNING_WINDOW_MS = 10 * 60 * 1000;

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

type RecoveryJobRow = typeof documentParseJobs.$inferSelect;

const DEFAULT_RECOVERY_ACTOR = "ops-operator";
const PG_UNIQUE_VIOLATION = "23505";

@Injectable()
export class OpsService {
  private readonly env = loadApiEnv();

  constructor(
    private readonly database: DatabaseService,
    private readonly answersService: AnswersService,
    private readonly documentsService: DocumentsService,
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
      this.getWorkerHealthItem(),
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

  async listDiagnosticSamples(query: OpsDiagnosticSampleListQuery): Promise<OpsDiagnosticSampleListResponse> {
    this.validateDiagnosticSampleQuery(query);

    return listDiagnosticSamplesFromDb(this.database.db, {
      origin: query.origin,
      window: this.normalizeTrendWindow(query.window ?? "24h"),
      page: query.page ?? 1,
      pageSize: query.page_size ?? 20,
      sampleKind: query.sample_kind,
      clusterKey: query.cluster_key,
      deploymentRecordId: query.deployment_record_id
    });
  }

  async getAnswerSessionReplay(sessionId: string): Promise<OpsAnswerSessionReplayResponse> {
    const [session, retrieval] = await Promise.all([
      this.answersService.getAnswer(sessionId),
      this.answersService.getAnswerRetrieval(sessionId)
    ]);

    return buildAnswerSessionReplayResponse({
      session,
      retrieval
    });
  }

  async getDocumentReplay(documentId: string): Promise<OpsDocumentReplayResponse> {
    const [document, timeline, evidence, relatedAnswerSessionCount] = await Promise.all([
      this.documentsService.getDocument(documentId),
      this.documentsService.getDocumentTimeline(documentId),
      this.documentsService.getDocumentEvidence(documentId),
      countRelatedAnswerSessionsForDocument(this.database.db, documentId)
    ]);

    return buildDocumentReplayResponse({
      document,
      timeline,
      evidence,
      relatedAnswerSessionCount
    });
  }

  async getDeploymentCompare(query: OpsDeploymentCompareQuery): Promise<OpsDeploymentCompareResponse> {
    const window = this.normalizeTrendWindow(query.window ?? "24h");
    const [deployment] = await this.database.db
      .select()
      .from(deploymentRecords)
      .where(eq(deploymentRecords.id, query.deployment_record_id))
      .limit(1);

    if (!deployment) {
      throw new NotFoundException("Deployment record not found");
    }

    const [previousDeployment, relatedEvaluationRunRef, compareSamples] = await Promise.all([
      this.getPreviousDeploymentRecord(deployment.environment, deployment.deployedAt),
      this.getEvaluationRunRefByCommitSha(deployment.commitSha),
      getDeploymentCompareSamples({
        db: this.database.db,
        deployment,
        window,
        sampleKind: query.sample_kind
      })
    ]);

    return buildDeploymentCompareResponse({
      deployment,
      previousDeployment,
      relatedEvaluationRunRef,
      window,
      beforeSamples: compareSamples.beforeSamples,
      affectedSamples: compareSamples.affectedSamples
    });
  }

  async listRecoveryCandidates(query: OpsRecoveryCandidateListQuery): Promise<OpsRecoveryCandidateListResponse> {
    const page = query.page ?? 1;
    const pageSize = query.page_size ?? 20;
    const sourceType = query.source_type ?? "diagnostic_sample";
    const samples = await this.getRecoverySourceSamples(sourceType, query.source_ref);
    const documentMap = await this.getRecoveryDocumentMap(samples);
    const allCandidates = filterRecoveryCandidates(
      samples.map((sample) =>
        buildRecoveryCandidate({
          sample,
          sourceType,
          sourceRef: query.source_ref,
          document: documentMap.get(sample.source_id) ?? null
        })
      ),
      {
        actionType: query.action_type,
        riskLevel: query.risk_level,
        recommendationState: query.recommendation_state
      }
    );
    const start = (page - 1) * pageSize;

    return {
      generated_at: new Date().toISOString(),
      page,
      page_size: pageSize,
      total: allCandidates.length,
      items: allCandidates.slice(start, start + pageSize)
    };
  }

  async previewRecoveryAction(
    request: OpsRecoveryActionPreviewRequest
  ): Promise<OpsRecoveryActionPreviewResponse> {
    this.validateRecoveryTargetRefs(request);

    const generatedAt = new Date();
    const idempotencyKey = this.buildRecoveryIdempotencyKey(request);
    const previewFacts = await this.getRecoveryPreviewFacts(request, generatedAt);

    return buildRecoveryPreview({
      request,
      generatedAt,
      idempotencyKey,
      sourceFacts: previewFacts.sourceFacts,
      beforeFacts: previewFacts.beforeFacts,
      preconditions: previewFacts.preconditions
    });
  }

  async createRecoveryAction(_request: OpsRecoveryActionCreateRequest): Promise<OpsRecoveryActionResponse> {
    const request = {
      ..._request,
      reason: _request.reason.trim()
    };

    if (!request.reason) {
      throw new BadRequestException("reason must not be empty");
    }

    const existing = await this.getRecoveryActionByIdempotencyKey(request.idempotency_key);
    if (existing) {
      return this.getRecoveryAction(existing.id);
    }

    const preview = await this.buildCreateRecoveryPreview(request);
    const blockedMessage =
      preview.action_type === "answer_diagnostic_rerun"
        ? "Phase 3B keeps answer diagnostic rerun read-only. Review the replay manually."
        : preview.blocked_reason;

    const now = new Date();
    const insertValues = {
      id: randomUUID(),
      candidateId: request.candidate_id,
      actionType: preview.action_type,
      targetType: preview.target_type,
      targetRefs: preview.target_refs as unknown as Record<string, unknown>[],
      status: blockedMessage ? "blocked" : "queued",
      actor: DEFAULT_RECOVERY_ACTOR,
      reason: request.reason,
      idempotencyKey: preview.idempotency_key,
      previewId: preview.preview_id,
      sourceFacts: preview.source_facts as unknown as Record<string, unknown>,
      preview: preview as unknown as Record<string, unknown>,
      beforeFacts: preview.before_facts as unknown as Record<string, unknown>,
      afterFacts: blockedMessage ? (preview.before_facts as unknown as Record<string, unknown>) : null,
      queueJobRefs: [] as Record<string, unknown>[],
      diagnosisCode: blockedMessage ? this.getRecoveryFactDiagnosisCode(preview.before_facts) : null,
      errorMessage: blockedMessage,
      completedAt: blockedMessage ? now : null,
      updatedAt: now
    } satisfies typeof operatorRecoveryActions.$inferInsert;

    let action: RecoveryActionRow;
    try {
      [action] = await this.database.db.insert(operatorRecoveryActions).values(insertValues).returning();
    } catch (error) {
      if (this.isUniqueViolation(error)) {
        const duplicate = await this.getRecoveryActionByIdempotencyKey(request.idempotency_key);
        if (duplicate) {
          return this.getRecoveryAction(duplicate.id);
        }
      }

      throw error;
    }

    if (blockedMessage) {
      return toRecoveryActionResponse(action);
    }

    try {
      const targetId = preview.target_refs[0]?.id;
      const execution =
        preview.action_type === "document_retry"
          ? await this.documentsService.retryDocument(targetId)
          : await this.documentsService.reindexDocument(targetId);
      const [updated] = await this.database.db
        .update(operatorRecoveryActions)
        .set({
          queueJobRefs: [buildRecoveryJobRef(execution.job_id)] as unknown as Record<string, unknown>[],
          updatedAt: new Date()
        })
        .where(eq(operatorRecoveryActions.id, action.id))
        .returning();

      return toRecoveryActionResponse(updated ?? action);
    } catch (error) {
      const afterFacts = await this.captureRecoveryAfterFacts(
        preview.action_type,
        preview.target_type,
        preview.target_refs[0]?.id ?? null,
        new Date()
      );
      const [failed] = await this.database.db
        .update(operatorRecoveryActions)
        .set({
          status: "failed",
          diagnosisCode:
            this.getRecoveryFactDiagnosisCode(afterFacts) ?? this.getRecoveryFactDiagnosisCode(preview.before_facts),
          errorMessage: this.getRecoveryErrorMessage(error),
          afterFacts: afterFacts as unknown as Record<string, unknown> | null,
          completedAt: new Date(),
          updatedAt: new Date()
        })
        .where(eq(operatorRecoveryActions.id, action.id))
        .returning();

      return toRecoveryActionResponse(failed ?? action);
    }
  }

  async getRecoveryAction(actionId: string): Promise<OpsRecoveryActionResponse> {
    const action = await this.getRecoveryActionById(actionId);
    const reconciled = await this.reconcileRecoveryAction(action);
    return toRecoveryActionResponse(reconciled);
  }

  async getRecoveryActionAudit(actionId: string): Promise<OpsRecoveryActionAuditResponse> {
    const action = await this.getRecoveryActionById(actionId);
    const reconciled = await this.reconcileRecoveryAction(action);
    return toRecoveryActionAuditResponse(reconciled, new Date());
  }

  async getRollbackPlan(query: OpsRollbackPlanQuery): Promise<OpsRollbackPlanResponse> {
    const compare = await this.getDeploymentCompare({
      deployment_record_id: query.deployment_record_id,
      window: query.window
    });

    return {
      generated_at: new Date().toISOString(),
      deployment_record_id: query.deployment_record_id,
      compare_ref: {
        method: "GET",
        path: `/api/v1/ops/deployments/compare?deployment_record_id=${query.deployment_record_id}${
          query.window ? `&window=${query.window}` : ""
        }`
      },
      affected_samples: compare.affected_samples,
      quality_delta_summary: compare.delta_summary,
      smoke_summary: `Deployment smoke status is ${compare.deployment.smoke_status}.`,
      confidence: compare.affected_samples.length > 0 ? "medium" : "low",
      missing_evidence: compare.deployment.smoke_status === "unknown" ? ["deployment_smoke_status"] : [],
      manual_checklist: [
        "Review deployment compare affected samples.",
        "Verify smoke evidence and production health manually.",
        "Follow the production rollback runbook outside xRag if rollback is required."
      ]
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

  private validateRecoveryTargetRefs(request: OpsRecoveryActionPreviewRequest) {
    if (request.target_refs.length === 0) {
      throw new BadRequestException("target_refs must contain at least one target");
    }

    if (request.target_refs.length !== 1) {
      throw new BadRequestException("Phase 3B recovery preview supports exactly one target_ref");
    }

    const mismatchedTarget = request.target_refs.find((targetRef) => targetRef.type !== request.target_type);
    if (mismatchedTarget) {
      throw new BadRequestException("target_refs type must match target_type");
    }

    if (request.action_type === "answer_diagnostic_rerun" && request.target_type !== "answer_session") {
      throw new BadRequestException("answer_diagnostic_rerun requires target_type answer_session");
    }

    if (request.action_type !== "answer_diagnostic_rerun" && request.target_type !== "document") {
      throw new BadRequestException("document recovery actions require target_type document");
    }
  }

  private buildRecoveryIdempotencyKey(request: OpsRecoveryActionPreviewRequest) {
    return createHash("sha256")
      .update(
        JSON.stringify({
          candidate_id: request.candidate_id,
          action_type: request.action_type,
          target_type: request.target_type,
          target_refs: request.target_refs
        })
      )
      .digest("hex");
  }

  private async getRecoverySourceSamples(sourceType: OpsRecoveryCandidateSourceType, sourceRef?: string) {
    if (sourceType === "answer_session_replay") {
      if (!sourceRef) {
        throw new BadRequestException("source_ref is required for answer_session_replay recovery candidates");
      }

      return [(await this.getAnswerSessionReplay(sourceRef)).sample];
    }

    if (sourceType === "document_replay") {
      if (!sourceRef) {
        throw new BadRequestException("source_ref is required for document_replay recovery candidates");
      }

      return [(await this.getDocumentReplay(sourceRef)).sample];
    }

    if (sourceType === "deployment_compare") {
      if (!sourceRef) {
        throw new BadRequestException("source_ref is required for deployment_compare recovery candidates");
      }

      const compare = await this.getDeploymentCompare({
        deployment_record_id: sourceRef,
        window: "24h"
      });
      return compare.affected_samples;
    }

    if (sourceRef) {
      return [await this.getDiagnosticSampleByRef(sourceRef)];
    }

    const samples = await this.listDiagnosticSamples({
      origin: "trend",
      window: "24h",
      page: 1,
      page_size: 100
    });
    return samples.items;
  }

  private async getDiagnosticSampleByRef(sourceRef: string) {
    const separatorIndex = sourceRef.indexOf(":");
    if (separatorIndex < 0) {
      throw new BadRequestException("diagnostic_sample source_ref must be a sample id");
    }

    const sampleKind = sourceRef.slice(0, separatorIndex);
    const sourceId = sourceRef.slice(separatorIndex + 1);

    if (sampleKind === "answer_session") {
      return (await this.getAnswerSessionReplay(sourceId)).sample;
    }

    if (sampleKind === "document") {
      return (await this.getDocumentReplay(sourceId)).sample;
    }

    throw new BadRequestException("Unsupported diagnostic_sample source_ref");
  }

  private async getRecoveryDocumentMap(samples: Awaited<ReturnType<OpsService["getRecoverySourceSamples"]>>) {
    const documentIds = [...new Set(samples.filter((sample) => sample.sample_kind === "document_pipeline").map((sample) => sample.source_id))];
    const entries = await Promise.all(
      documentIds.map(async (documentId) => {
        try {
          return [documentId, await this.documentsService.getDocument(documentId)] as const;
        } catch (error) {
          if (error instanceof NotFoundException) {
            return [documentId, null] as const;
          }

          throw error;
        }
      })
    );

    return new Map(entries);
  }

  private async getRecoveryPreviewFacts(request: OpsRecoveryActionPreviewRequest, generatedAt: Date) {
    const targetRef = request.target_refs[0];

    if (request.target_type === "document") {
      const document = await this.documentsService.getDocument(targetRef.id);
      const facts = buildDocumentRecoveryFactSnapshot({
        document,
        actionType: request.action_type,
        capturedAt: generatedAt
      });

      return {
        sourceFacts: facts,
        beforeFacts: facts,
        preconditions: getDocumentPreviewPreconditions(request.action_type, document)
      };
    }

    const session = await this.answersService.getAnswer(targetRef.id);
    const facts = buildAnswerRecoveryFactSnapshot({
      session,
      actionType: request.action_type,
      capturedAt: generatedAt
    });

    return {
      sourceFacts: facts,
      beforeFacts: facts,
      preconditions: getAnswerPreviewPreconditions(session)
    };
  }

  private async buildCreateRecoveryPreview(request: OpsRecoveryActionCreateRequest) {
    let candidate;
    try {
      candidate = parseRecoveryCandidateId(request.candidate_id);
    } catch {
      throw new BadRequestException("candidate_id is invalid");
    }

    const preview = await this.previewRecoveryAction({
      candidate_id: request.candidate_id,
      action_type: candidate.actionType,
      target_type: candidate.targetType,
      target_refs: [{ type: candidate.targetType, id: candidate.targetId }]
    });

    if (preview.preview_id !== request.preview_id) {
      throw new BadRequestException("preview_id does not match the current recovery preview");
    }

    if (preview.idempotency_key !== request.idempotency_key) {
      throw new BadRequestException("idempotency_key does not match the current recovery preview");
    }

    return preview;
  }

  private async getRecoveryActionById(actionId: string) {
    const [action] = await this.database.db
      .select()
      .from(operatorRecoveryActions)
      .where(eq(operatorRecoveryActions.id, actionId))
      .limit(1);

    if (!action) {
      throw new NotFoundException("Recovery action not found");
    }

    return action;
  }

  private async getRecoveryActionByIdempotencyKey(idempotencyKey: string) {
    const [action] = await this.database.db
      .select()
      .from(operatorRecoveryActions)
      .where(eq(operatorRecoveryActions.idempotencyKey, idempotencyKey))
      .limit(1);

    return action ?? null;
  }

  private async reconcileRecoveryAction(action: RecoveryActionRow): Promise<RecoveryActionRow> {
    if (action.status !== "queued" && action.status !== "running") {
      return action;
    }

    if (action.targetType !== "document") {
      return action;
    }

    return this.reconcileDocumentRecoveryAction(action);
  }

  private async reconcileDocumentRecoveryAction(action: RecoveryActionRow): Promise<RecoveryActionRow> {
    const documentId = action.targetRefs[0]?.id;
    if (typeof documentId !== "string" || !documentId) {
      return action;
    }

    const now = new Date();
    const [document, jobs] = await Promise.all([
      this.documentsService.getDocument(documentId).catch((error) => {
        if (error instanceof NotFoundException) {
          return null;
        }

        throw error;
      }),
      this.listRecoveryDocumentJobs(documentId, action.createdAt)
    ]);
    const queueJobRefs = jobs.map((job) => buildRecoveryJobRef(job.id));
    const latestFailedJob = this.getLatestFailedRecoveryJob(jobs);
    const earliestStartedAt = jobs
      .map((job) => job.startedAt)
      .filter((startedAt): startedAt is Date => startedAt instanceof Date)
      .sort((left, right) => left.getTime() - right.getTime())[0];
    const latestFinishedAt = jobs
      .map((job) => job.finishedAt)
      .filter((finishedAt): finishedAt is Date => finishedAt instanceof Date)
      .sort((left, right) => right.getTime() - left.getTime())[0];
    const hasQueuedJobs = jobs.some((job) => job.status === "queued");
    const hasRunningJobs = jobs.some((job) => job.status === "running");
    const hasStartedJobs = jobs.some((job) => job.startedAt || job.status === "succeeded");

    if (!document) {
      return this.updateRecoveryActionIfChanged(action, {
        status: "failed",
        queueJobRefs,
        diagnosisCode: action.diagnosisCode as OpsRecoveryActionResponse["diagnosis_code"],
        errorMessage: "Recovery target document no longer exists.",
        completedAt: latestFinishedAt ?? now
      });
    }

    if (latestFailedJob || document.parse_status === "failed" || document.index_status === "failed") {
      const afterFacts = buildDocumentRecoveryFactSnapshot({
        document,
        actionType: action.actionType,
        capturedAt: now
      });
      return this.updateRecoveryActionIfChanged(action, {
        status: "failed",
        queueJobRefs,
        startedAt: action.startedAt ?? earliestStartedAt ?? null,
        completedAt: latestFailedJob?.finishedAt ?? latestFinishedAt ?? now,
        diagnosisCode:
          (latestFailedJob?.diagnosisCode as OpsRecoveryActionResponse["diagnosis_code"]) ??
          (document.diagnosis_code as OpsRecoveryActionResponse["diagnosis_code"]) ??
          null,
        errorMessage: latestFailedJob?.errorMessage ?? document.diagnosis_summary ?? "Recovery action failed.",
        afterFacts
      });
    }

    if (this.isDocumentRecoverySuccessful(action.actionType, document)) {
      const afterFacts = buildDocumentRecoveryFactSnapshot({
        document,
        actionType: action.actionType,
        capturedAt: now
      });
      return this.updateRecoveryActionIfChanged(action, {
        status: "succeeded",
        queueJobRefs,
        startedAt: action.startedAt ?? earliestStartedAt ?? null,
        completedAt: latestFinishedAt ?? now,
        diagnosisCode: null,
        errorMessage: null,
        afterFacts
      });
    }

    if (hasRunningJobs || hasQueuedJobs || this.isDocumentRecoveryInFlight(document)) {
      const nextStatus = hasRunningJobs || hasStartedJobs ? "running" : "queued";
      return this.updateRecoveryActionIfChanged(action, {
        status: nextStatus,
        queueJobRefs,
        startedAt: nextStatus === "running" ? action.startedAt ?? earliestStartedAt ?? action.createdAt : null
      });
    }

    const afterFacts = buildDocumentRecoveryFactSnapshot({
      document,
      actionType: action.actionType,
      capturedAt: now
    });
    return this.updateRecoveryActionIfChanged(action, {
      status: "failed",
      queueJobRefs,
      startedAt: action.startedAt ?? earliestStartedAt ?? null,
      completedAt: latestFinishedAt ?? now,
      diagnosisCode: document.diagnosis_code ?? null,
      errorMessage: document.diagnosis_summary ?? "Recovery action did not reach a successful terminal state.",
      afterFacts
    });
  }

  private async listRecoveryDocumentJobs(documentId: string, actionCreatedAt: Date) {
    return this.database.db
      .select()
      .from(documentParseJobs)
      .where(
        and(eq(documentParseJobs.documentId, documentId), gte(documentParseJobs.createdAt, actionCreatedAt))
      )
      .orderBy(asc(documentParseJobs.createdAt), asc(documentParseJobs.attempt));
  }

  private getLatestFailedRecoveryJob(jobs: RecoveryJobRow[]) {
    return [...jobs].reverse().find((job) => job.status === "failed" || job.status === "dead") ?? null;
  }

  private isDocumentRecoverySuccessful(
    actionType: OpsRecoveryActionPreviewResponse["action_type"],
    document: Awaited<ReturnType<DocumentsService["getDocument"]>>
  ) {
    if (actionType === "document_retry") {
      return document.parse_status === "success" && document.index_status === "ready" && document.citation_ready;
    }

    return document.index_status === "ready" && document.citation_ready;
  }

  private isDocumentRecoveryInFlight(document: Awaited<ReturnType<DocumentsService["getDocument"]>>) {
    return (
      document.parse_status === "pending" ||
      document.parse_status === "processing" ||
      document.ocr_status === "queued" ||
      document.ocr_status === "processing" ||
      document.index_status === "queued" ||
      document.index_status === "chunking" ||
      document.index_status === "embedding"
    );
  }

  private async updateRecoveryActionIfChanged(
    action: RecoveryActionRow,
    next: {
      status?: RecoveryActionRow["status"];
      queueJobRefs?: Array<{ method: "GET"; path: string }>;
      diagnosisCode?: OpsRecoveryActionResponse["diagnosis_code"];
      errorMessage?: string | null;
      startedAt?: Date | null;
      completedAt?: Date | null;
      afterFacts?: OpsRecoveryFactSnapshot | null;
    }
  ) {
    const queueJobRefsChanged =
      next.queueJobRefs !== undefined &&
      JSON.stringify(action.queueJobRefs ?? []) !== JSON.stringify(next.queueJobRefs);
    const afterFactsChanged =
      next.afterFacts !== undefined &&
      JSON.stringify(readRecoveryFactSnapshot(action.afterFacts)) !== JSON.stringify(next.afterFacts);
    const changed =
      (next.status !== undefined && action.status !== next.status) ||
      queueJobRefsChanged ||
      (next.diagnosisCode !== undefined && action.diagnosisCode !== next.diagnosisCode) ||
      (next.errorMessage !== undefined && action.errorMessage !== next.errorMessage) ||
      (next.startedAt !== undefined &&
        (action.startedAt?.toISOString() ?? null) !== (next.startedAt?.toISOString() ?? null)) ||
      (next.completedAt !== undefined &&
        (action.completedAt?.toISOString() ?? null) !== (next.completedAt?.toISOString() ?? null)) ||
      afterFactsChanged;

    if (!changed) {
      return action;
    }

    const [updated] = await this.database.db
      .update(operatorRecoveryActions)
      .set({
        status: next.status ?? action.status,
        queueJobRefs:
          next.queueJobRefs !== undefined
            ? (next.queueJobRefs as unknown as Record<string, unknown>[])
            : action.queueJobRefs,
        diagnosisCode: next.diagnosisCode !== undefined ? next.diagnosisCode : action.diagnosisCode,
        errorMessage: next.errorMessage !== undefined ? next.errorMessage : action.errorMessage,
        startedAt: next.startedAt !== undefined ? next.startedAt : action.startedAt,
        completedAt: next.completedAt !== undefined ? next.completedAt : action.completedAt,
        afterFacts:
          next.afterFacts !== undefined
            ? (next.afterFacts as unknown as Record<string, unknown> | null)
            : action.afterFacts,
        updatedAt: new Date()
      })
      .where(eq(operatorRecoveryActions.id, action.id))
      .returning();

    return updated ?? action;
  }

  private async captureRecoveryAfterFacts(
    actionType: OpsRecoveryActionPreviewResponse["action_type"],
    targetType: OpsRecoveryActionPreviewResponse["target_type"],
    targetId: string | null,
    capturedAt: Date
  ) {
    if (!targetId) {
      return null;
    }

    try {
      if (targetType === "document") {
        const document = await this.documentsService.getDocument(targetId);
        return buildDocumentRecoveryFactSnapshot({
          document,
          actionType,
          capturedAt
        });
      }

      const session = await this.answersService.getAnswer(targetId);
      return buildAnswerRecoveryFactSnapshot({
        session,
        actionType,
        capturedAt
      });
    } catch (error) {
      if (error instanceof NotFoundException) {
        return null;
      }

      throw error;
    }
  }

  private getRecoveryFactDiagnosisCode(facts: OpsRecoveryFactSnapshot | null) {
    const diagnosisCode = facts?.facts?.diagnosis_code;
    return typeof diagnosisCode === "string"
      ? (diagnosisCode as OpsRecoveryActionResponse["diagnosis_code"])
      : null;
  }

  private getRecoveryErrorMessage(error: unknown) {
    if (error instanceof HttpException) {
      const response = error.getResponse();
      if (typeof response === "string") {
        return response;
      }

      if (response && typeof response === "object") {
        const message = (response as { message?: string | string[] }).message;
        if (Array.isArray(message)) {
          return message.join("; ");
        }

        if (typeof message === "string") {
          return message;
        }
      }

      return error.message;
    }

    return error instanceof Error ? error.message : "Recovery action execution failed";
  }

  private isUniqueViolation(error: unknown) {
    return (
      typeof error === "object" &&
      error !== null &&
      "code" in error &&
      (error as { code?: string }).code === PG_UNIQUE_VIOLATION
    );
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

  private validateDiagnosticSampleQuery(query: OpsDiagnosticSampleListQuery) {
    if (query.origin === "incident_cluster" && !query.cluster_key) {
      throw new BadRequestException("cluster_key is required when origin=incident_cluster");
    }

    if (query.origin === "release_compare" && !query.deployment_record_id) {
      throw new BadRequestException("deployment_record_id is required when origin=release_compare");
    }
  }

  private async getPreviousDeploymentRecord(environment: string, deployedAt: Date) {
    const [record] = await this.database.db
      .select({
        id: deploymentRecords.id,
        currentImageTag: deploymentRecords.currentImageTag
      })
      .from(deploymentRecords)
      .where(sql`${deploymentRecords.environment} = ${environment} and ${deploymentRecords.deployedAt} < ${deployedAt}`)
      .orderBy(desc(deploymentRecords.deployedAt), desc(deploymentRecords.createdAt))
      .limit(1);

    return record ?? null;
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

  private async getWorkerHealthItem() {
    try {
      await this.queueService.checkConnection();
    } catch (error) {
      return {
        name: "worker",
        status: "warning" as OpsServiceStatus,
        detail: error instanceof Error ? error.message : "queue unavailable"
      };
    }

    const staleCutoff = new Date(Date.now() - WORKER_STALL_WARNING_WINDOW_MS);
    const [staleAnswerSessionsResult, staleDocumentJobsResult] = await Promise.all([
      this.database.db
        .select({
          count: sql<number>`count(*)::int`
        })
        .from(answerSessions)
        .where(
          sql`${answerSessions.status} in ('idle', 'retrieving', 'synthesizing') and ${answerSessions.updatedAt} < ${staleCutoff}`
        ),
      this.database.db
        .select({
          count: sql<number>`count(*)::int`
        })
        .from(documentParseJobs)
        .where(
          sql`${documentParseJobs.status} in ('queued', 'running') and coalesce(${documentParseJobs.startedAt}, ${documentParseJobs.createdAt}) < ${staleCutoff}`
        )
    ]);

    const staleAnswerSessions = staleAnswerSessionsResult[0]?.count ?? 0;
    const staleDocumentJobs = staleDocumentJobsResult[0]?.count ?? 0;
    if (staleAnswerSessions === 0 && staleDocumentJobs === 0) {
      return {
        name: "worker",
        status: "healthy" as OpsServiceStatus,
        detail: "queue reachable; no stale answer sessions or document jobs older than 10m"
      };
    }

    const details: string[] = [];
    if (staleAnswerSessions > 0) {
      details.push(`${staleAnswerSessions} stale active answer session(s)`);
    }
    if (staleDocumentJobs > 0) {
      details.push(`${staleDocumentJobs} stale queued/running document job(s)`);
    }

    return {
      name: "worker",
      status: "warning" as OpsServiceStatus,
      detail: `queue reachable; ${details.join("; ")} older than 10m`
    };
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
