import { Injectable } from "@nestjs/common";
import { eq, sql } from "drizzle-orm";
import type {
  IncidentSeverity,
  IncidentSource,
  IncidentStatus,
  LatestDeploymentResponse,
  OpsAnswerSummaryResponse,
  OpsHealthSummaryResponse,
  OpsIncidentListResponse,
  OpsServiceStatus
} from "@xrag/shared-types";
import { loadApiEnv } from "../config/env";
import { DatabaseService } from "../database/database.service";
import { answerCitations, answerSessions, documents } from "../database/schema";
import { JobsRepository } from "../jobs/jobs.repository";
import { QueueService } from "../queue/queue.service";
import { StorageService } from "../storage/storage.service";
import { UploadsRepository } from "../uploads/uploads.repository";

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
    const [jobIncidents, uploadIncidents] = await Promise.all([
      this.jobsRepository.listRecentIncidentCandidates(12),
      this.uploadsRepository.listRecentFailedUploads(8)
    ]);

    const items = [
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
          external_url: null
        };
      }),
      ...uploadIncidents.map((upload) => ({
        incident_ref: this.buildIncidentRef("UPL", upload.id),
        source: "upload" as IncidentSource,
        severity: this.getIncidentSeverity(upload.errorCode, "upload"),
        status: "open" as IncidentStatus,
        title: this.getUploadIncidentTitle(upload.errorCode),
        summary: upload.errorMessage || `上传 ${upload.fileName} 失败，请检查对象存储与分片状态。`,
        external_url: null
      }))
    ]
      .sort((left, right) => left.incident_ref.localeCompare(right.incident_ref) * -1)
      .slice(0, 20);

    return {
      items
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

  async getLatestDeployment(): Promise<LatestDeploymentResponse> {
    return {
      current_image_tag: process.env.XRAG_IMAGE_TAG || null,
      previous_stable_image_tag: process.env.XRAG_PREVIOUS_IMAGE_TAG || null,
      last_smoke_status: (process.env.XRAG_LAST_SMOKE_STATUS as LatestDeploymentResponse["last_smoke_status"]) || "unknown",
      last_smoke_at: process.env.XRAG_LAST_SMOKE_AT || null
    };
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
