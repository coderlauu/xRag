import { Module } from "@nestjs/common";
import { AnswersModule } from "./answers/answers.module";
import { DatabaseModule } from "./database/database.module";
import { DocumentsModule } from "./documents/documents.module";
import { HealthModule } from "./health/health.module";
import { JobsModule } from "./jobs/jobs.module";
import { OpsModule } from "./ops/ops.module";
import { QueueModule } from "./queue/queue.module";
import { StorageModule } from "./storage/storage.module";
import { TagsModule } from "./tags/tags.module";
import { UploadsModule } from "./uploads/uploads.module";

@Module({
  imports: [
    DatabaseModule,
    QueueModule,
    StorageModule,
    HealthModule,
    AnswersModule,
    OpsModule,
    TagsModule,
    JobsModule,
    DocumentsModule,
    UploadsModule
  ]
})
export class AppModule {}
