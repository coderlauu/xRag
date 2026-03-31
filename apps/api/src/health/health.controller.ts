import { Controller, Get } from "@nestjs/common";

@Controller("api/v1/health")
export class HealthController {
  @Get()
  getHealth() {
    return { status: "ok" };
  }

  @Get("ready")
  getReadiness() {
    return {
      status: "ready",
      checks: {
        postgres: "stubbed",
        redis: "stubbed",
        objectStorage: "stubbed"
      }
    };
  }
}
