import { Module } from "@nestjs/common";
import { AnswersModule } from "../answers/answers.module";
import { DocumentsModule } from "../documents/documents.module";
import { JobsModule } from "../jobs/jobs.module";
import { UploadsModule } from "../uploads/uploads.module";
import { OpsController } from "./ops.controller";
import { OpsService } from "./ops.service";

@Module({
  imports: [AnswersModule, DocumentsModule, JobsModule, UploadsModule],
  controllers: [OpsController],
  providers: [OpsService]
})
export class OpsModule {}
