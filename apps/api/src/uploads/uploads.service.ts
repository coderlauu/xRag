import { BadRequestException, Injectable, NotFoundException } from "@nestjs/common";
import { randomUUID } from "node:crypto";
import type { UploadCompleteResponse, UploadInitiateResponse } from "@xrag/shared-types";
import { buildSearchText, normalizeWhitespace, sanitizeFileName } from "../common/document-utils";
import { loadApiEnv } from "../config/env";
import { DatabaseService } from "../database/database.service";
import { DocumentsRepository } from "../documents/documents.repository";
import { JobsRepository } from "../jobs/jobs.repository";
import { TagsRepository } from "../tags/tags.repository";
import { UploadCompleteRequestDto, UploadInitiateRequestDto } from "./uploads.dto";
import { UploadsRepository } from "./uploads.repository";

@Injectable()
export class UploadsService {
  private readonly env = loadApiEnv();

  constructor(
    private readonly database: DatabaseService,
    private readonly uploadsRepository: UploadsRepository,
    private readonly documentsRepository: DocumentsRepository,
    private readonly tagsRepository: TagsRepository,
    private readonly jobsRepository: JobsRepository
  ) {}

  async initiateUpload(body: UploadInitiateRequestDto): Promise<UploadInitiateResponse> {
    const uploadId = randomUUID();
    const now = new Date();
    const objectKey = [
      "uploads",
      now.getUTCFullYear(),
      String(now.getUTCMonth() + 1).padStart(2, "0"),
      String(now.getUTCDate()).padStart(2, "0"),
      uploadId,
      sanitizeFileName(body.file_name)
    ].join("/");

    const upload = await this.uploadsRepository.createUpload({
      id: uploadId,
      fileName: body.file_name.trim(),
      mimeType: body.mime_type.trim(),
      fileSize: body.file_size,
      objectKey
    });

    return {
      upload_id: upload.id,
      object_key: upload.objectKey,
      upload_method: "presigned_put",
      upload_url: `${this.env.uploadUrlBase.replace(/\/$/, "")}/${upload.objectKey}`,
      headers: {
        "content-type": upload.mimeType
      },
      expires_in: this.env.uploadUrlExpiresIn
    };
  }

  async completeUpload(uploadId: string, body: UploadCompleteRequestDto): Promise<UploadCompleteResponse> {
    return this.database.db.transaction(async (tx) => {
      const upload = await this.uploadsRepository.getUploadById(uploadId, tx);
      if (!upload) {
        throw new NotFoundException("Upload not found");
      }

      if (upload.status === "expired") {
        throw new BadRequestException("Upload has expired");
      }

      if (upload.status === "completed") {
        throw new BadRequestException("Upload already completed");
      }

      const title = normalizeWhitespace(body.title);
      const tagRows = await this.tagsRepository.upsertTags(body.tags, tx);
      const document = await this.documentsRepository.createDocument(
        {
          id: randomUUID(),
          title,
          contentPreview: "",
          searchText: buildSearchText({
            title,
            contentClean: null,
            tags: tagRows.map((tag) => tag.name),
            fileName: upload.fileName,
            sourceUrl: null
          }),
          sourceType: "file",
          sourceOrigin: "upload",
          fileName: upload.fileName,
          mimeType: upload.mimeType,
          fileSize: upload.fileSize,
          objectKey: upload.objectKey,
          contentSha256: body.checksum_sha256,
          parseStatus: "pending"
        },
        tx
      );

      await this.documentsRepository.replaceDocumentTags(
        document.id,
        tagRows.map((tag) => tag.id),
        tx
      );
      await this.uploadsRepository.markUploadCompleted(uploadId, body.checksum_sha256, tx);

      const job = await this.jobsRepository.createJob(
        {
          id: randomUUID(),
          documentId: document.id,
          queueJobId: null,
          jobType: "parse_document",
          status: "queued",
          attempt: 1
        },
        tx
      );

      return {
        document_id: document.id,
        job_id: job.id,
        parse_status: document.parseStatus
      };
    });
  }
}
