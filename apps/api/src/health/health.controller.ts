import { Controller, Get, ServiceUnavailableException } from "@nestjs/common";
import { DatabaseService } from "../database/database.service";

@Controller("api/v1/health")
export class HealthController {
  constructor(private readonly database: DatabaseService) {}

  @Get()
  getHealth() {
    return { status: "ok" };
  }

  @Get("ready")
  async getReadiness() {
    try {
      await this.database.checkConnection();
    } catch {
      throw new ServiceUnavailableException("Postgres is unavailable");
    }

    return {
      status: "ready",
      checks: {
        postgres: "ok",
        redis: "not-wired",
        objectStorage: "not-wired"
      }
    };
  }
}
