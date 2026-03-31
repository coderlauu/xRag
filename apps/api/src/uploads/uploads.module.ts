import { Module } from "@nestjs/common";
import { DocumentsModule } from "../documents/documents.module";
import { JobsModule } from "../jobs/jobs.module";
import { TagsModule } from "../tags/tags.module";
import { UploadsController } from "./uploads.controller";
import { UploadsRepository } from "./uploads.repository";
import { UploadsService } from "./uploads.service";

@Module({
  imports: [DocumentsModule, TagsModule, JobsModule],
  controllers: [UploadsController],
  providers: [UploadsRepository, UploadsService]
})
export class UploadsModule {}
