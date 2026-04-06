import { Controller, Get, ServiceUnavailableException } from "@nestjs/common";
import { ApiOkResponse, ApiOperation, ApiTags } from "@nestjs/swagger";
import { DatabaseService } from "../database/database.service";
import { QueueService } from "../queue/queue.service";
import { StorageService } from "../storage/storage.service";
import { HealthResponseDto, ReadinessResponseDto } from "./health.dto";

@ApiTags("health")
@Controller("api/v1/health")
export class HealthController {
  constructor(
    private readonly database: DatabaseService,
    private readonly queueService: QueueService,
    private readonly storageService: StorageService
  ) {}

  @Get()
  @ApiOperation({ summary: "Get service liveness" })
  @ApiOkResponse({ type: HealthResponseDto })
  getHealth() {
    return { status: "ok" };
  }

  @Get("ready")
  @ApiOperation({ summary: "Get dependency readiness" })
  @ApiOkResponse({ type: ReadinessResponseDto })
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
