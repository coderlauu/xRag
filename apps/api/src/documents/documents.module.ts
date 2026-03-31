import { Module } from "@nestjs/common";
import { JobsModule } from "../jobs/jobs.module";
import { TagsModule } from "../tags/tags.module";
import { DocumentsController } from "./documents.controller";
import { DocumentsRepository } from "./documents.repository";
import { DocumentsService } from "./documents.service";

@Module({
  imports: [TagsModule, JobsModule],
  controllers: [DocumentsController],
  providers: [DocumentsRepository, DocumentsService],
  exports: [DocumentsRepository]
})
export class DocumentsModule {}
