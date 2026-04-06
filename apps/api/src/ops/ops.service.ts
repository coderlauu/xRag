import { Injectable } from "@nestjs/common";
import type {
  IncidentSeverity,
  IncidentSource,
  IncidentStatus,
  LatestDeploymentResponse,
  OpsHealthSummaryResponse,
  OpsIncidentListResponse,
  OpsServiceStatus
} from "@xrag/shared-types";
import { loadApiEnv } from "../config/env";
import { DatabaseService } from "../database/database.service";
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
    const serviceChecks = await Promise.all([
      this.toHealthItem("api", Promise.resolve("ready")),
      this.toHealthItem("worker", this.queueService.checkConnection().then(() => "queue reachable")),
      this.toHealthItem("storage", this.storageService.checkConnection().then(() => "reachable")),
      this.toHealthItem("database", this.database.checkConnection().then(() => "reachable"))
    ]);

    return {
      services: serviceChecks,
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
        const source = job.jobType === "refresh_search_projection" ? "ci" : "parse";
        const title = this.getJobIncidentTitle(job.diagnosisCode, job.jobType);
        const summary = job.errorMessage || "解析任务失败，请查看任务错误与文档详情。";

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
    if (source === "ci") {
      return "medium";
    }

    if (code === "queue_backlog" || code === "object_missing_on_complete" || code === "pdf_parse_timeout") {
      return "high";
    }

    return "medium";
  }

  private getIncidentStatus(jobStatus: string): IncidentStatus {
    return jobStatus === "dead" ? "tracked" : "open";
  }

  private getJobIncidentTitle(code: string | null, jobType: string): string {
    switch (code) {
      case "pdf_parse_timeout":
        return "PDF 解析超时";
      case "pdf_parse_unsupported":
        return "PDF 无法解析";
      case "pdf_parse_empty_text":
        return "PDF 提取结果为空";
      case "queue_backlog":
        return "解析任务入队失败";
      default:
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
}
