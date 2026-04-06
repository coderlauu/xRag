import { Injectable } from "@nestjs/common";
import type {
  LatestDeploymentResponse,
  OpsHealthSummaryResponse,
  OpsIncidentListResponse,
  OpsServiceStatus
} from "@xrag/shared-types";
import { loadApiEnv } from "../config/env";
import { DatabaseService } from "../database/database.service";
import { QueueService } from "../queue/queue.service";
import { StorageService } from "../storage/storage.service";

@Injectable()
export class OpsService {
  private readonly env = loadApiEnv();

  constructor(
    private readonly database: DatabaseService,
    private readonly queueService: QueueService,
    private readonly storageService: StorageService
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
    return {
      items: []
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
}
