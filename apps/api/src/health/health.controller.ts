import { Controller, Get, ServiceUnavailableException } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";
import { QueueService } from "../queue/queue.service";
import { StorageService } from "../storage/storage.service";

@Controller("api/v1/health")
export class HealthController {
  constructor(
    private readonly database: DatabaseService,
    private readonly queueService: QueueService,
    private readonly storageService: StorageService
  ) {}

  @Get()
  getHealth() {
    return { status: "ok" };
  }

  @Get("ready")
  async getReadiness() {
    try {
      await this.database.checkConnection();
      await this.queueService.checkConnection();
      await this.storageService.checkConnection();
    } catch {
      throw new ServiceUnavailableException("Dependencies are unavailable");
    }

    return {
      status: "ready",
      checks: {
        postgres: "ok",
        redis: "ok",
        objectStorage: "ok"
      }
    };
  }
}
