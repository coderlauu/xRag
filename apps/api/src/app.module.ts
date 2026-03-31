import { Module } from "@nestjs/common";
import { DatabaseModule } from "./database/database.module";
import { DocumentsModule } from "./documents/documents.module";
import { HealthModule } from "./health/health.module";
import { JobsModule } from "./jobs/jobs.module";
import { TagsModule } from "./tags/tags.module";
import { UploadsModule } from "./uploads/uploads.module";

@Module({
  imports: [DatabaseModule, HealthModule, TagsModule, JobsModule, DocumentsModule, UploadsModule]
})
export class AppModule {}
