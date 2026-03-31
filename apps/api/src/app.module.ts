import { Module } from "@nestjs/common";
import { DocumentsModule } from "./documents/documents.module";
import { HealthModule } from "./health/health.module";
import { JobsModule } from "./jobs/jobs.module";
import { TagsModule } from "./tags/tags.module";
import { UploadsModule } from "./uploads/uploads.module";

@Module({
  imports: [HealthModule, DocumentsModule, UploadsModule, TagsModule, JobsModule]
})
export class AppModule {}
