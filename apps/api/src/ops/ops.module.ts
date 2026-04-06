import { Module } from "@nestjs/common";
import { JobsModule } from "../jobs/jobs.module";
import { UploadsModule } from "../uploads/uploads.module";
import { OpsController } from "./ops.controller";
import { OpsService } from "./ops.service";

@Module({
  imports: [JobsModule, UploadsModule],
  controllers: [OpsController],
  providers: [OpsService]
})
export class OpsModule {}
