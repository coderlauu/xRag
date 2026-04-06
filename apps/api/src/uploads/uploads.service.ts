import { BadRequestException, Injectable, NotFoundException } from "@nestjs/common";
import { randomUUID } from "node:crypto";
import type {
  CompletedUploadPart,
  DiagnosisCode,
  DocumentUploadStatus,
  UploadSessionStatus,
  UploadCompleteResponse,
  UploadInitiateResponse,
  UploadPartCompleteResponse,
  UploadPartUrlResponse
} from "@xrag/shared-types";
import { buildSearchText, normalizeWhitespace, sanitizeFileName } from "../common/document-utils";
import { DatabaseService } from "../database/database.service";
import { DocumentsRepository } from "../documents/documents.repository";
import { JobsRepository } from "../jobs/jobs.repository";
import { QueueService } from "../queue/queue.service";
import { StorageService } from "../storage/storage.service";
import { TagsRepository } from "../tags/tags.repository";
import {
  UploadCompleteRequestDto,
  UploadInitiateRequestDto,
  UploadPartCompleteRequestDto,
  UploadPartUrlRequestDto
} from "./uploads.dto";
import { UploadsRepository } from "./uploads.repository";

const MULTIPART_PART_SIZE_BYTES = 5 * 1024 * 1024;
const MULTIPART_THRESHOLD_BYTES = MULTIPART_PART_SIZE_BYTES * 2;

@Injectable()
export class UploadsService {
  constructor(
    private readonly database: DatabaseService,
    private readonly uploadsRepository: UploadsRepository,
    private readonly documentsRepository: DocumentsRepository,
    private readonly tagsRepository: TagsRepository,
    private readonly jobsRepository: JobsRepository,
    private readonly queueService: QueueService,
    private readonly storageService: StorageService
  ) {}

  async initiateUpload(body: UploadInitiateRequestDto): Promise<UploadInitiateResponse> {
    const uploadId = randomUUID();
    const now = new Date();
    const uploadMode = body.file_size > MULTIPART_THRESHOLD_BYTES ? "multipart" : "single";
    const partCount =
      uploadMode === "multipart" ? Math.ceil(body.file_size / MULTIPART_PART_SIZE_BYTES) : undefined;
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
      objectKey,
      checksumSha256: body.checksum_sha256,
      uploadMode,
      partCount
    });

    try {
      if (uploadMode === "multipart") {
        const multipart = await this.storageService.createMultipartUpload({
          objectKey: upload.objectKey,
          contentType: upload.mimeType
        });

        await this.uploadsRepository.updateUpload(upload.id, {
          providerUploadId: multipart.uploadId
        });

        return {
          upload_id: upload.id,
          upload_mode: "multipart",
          object_key: upload.objectKey,
          status: this.toUploadSessionStatus(upload.status),
          part_size_bytes: MULTIPART_PART_SIZE_BYTES,
          part_count: partCount,
          provider_upload_id: multipart.uploadId,
          expires_in: multipart.expiresIn
        };
      }

      const presigned = await this.storageService.createPresignedUpload({
        objectKey: upload.objectKey,
        contentType: upload.mimeType
      });

      return {
        upload_id: upload.id,
        upload_mode: "single",
        object_key: upload.objectKey,
        status: this.toUploadSessionStatus(upload.status),
        upload_method: "presigned_put",
        upload_url: presigned.uploadUrl,
        headers: {
          "content-type": upload.mimeType
        },
        expires_in: presigned.expiresIn
      };
    } catch (error) {
      await this.uploadsRepository.updateUpload(upload.id, {
        status: "failed",
        errorCode: "storage_presign_failed",
        errorMessage: error instanceof Error ? error.message : "Failed to create upload session"
      });

      this.throwUploadError(
        "storage_presign_failed",
        "未能创建对象存储上传会话。",
        true,
        { upload_id: upload.id }
      );
    }
  }

  async getUploadParts(uploadId: string, body: UploadPartUrlRequestDto): Promise<UploadPartUrlResponse> {
    const upload = await this.uploadsRepository.getUploadById(uploadId);
    if (!upload) {
      throw new NotFoundException("Upload not found");
    }

    if (upload.uploadMode !== "multipart") {
      throw new BadRequestException("Upload is not multipart");
    }

    if (!upload.providerUploadId || !upload.partCount) {
      throw new BadRequestException("Multipart session is not initialized");
    }

    const invalidPart = body.part_numbers.find((partNumber) => partNumber < 1 || partNumber > upload.partCount!);
    if (invalidPart) {
      this.throwUploadError(
        "upload_complete_invalid_parts",
        "请求的分片号超出当前上传会话范围。",
        false,
        { upload_id: upload.id, part_number: invalidPart }
      );
    }

    await this.uploadsRepository.ensureUploadParts(upload.id, body.part_numbers);

    const parts = await Promise.all(
      body.part_numbers.map(async (partNumber) => {
        const presigned = await this.storageService.createPresignedUploadPart({
          objectKey: upload.objectKey,
          uploadId: upload.providerUploadId!,
          partNumber
        });

        return {
          part_number: partNumber,
          upload_url: presigned.uploadUrl,
          headers: {}
        };
      })
    );

    return {
      upload_id: upload.id,
      parts
    };
  }

  async completeUploadPart(
    uploadId: string,
    partNumber: number,
    body: UploadPartCompleteRequestDto
  ): Promise<UploadPartCompleteResponse> {
    const upload = await this.uploadsRepository.getUploadById(uploadId);
    if (!upload) {
      throw new NotFoundException("Upload not found");
    }

    if (upload.uploadMode !== "multipart") {
      throw new BadRequestException("Upload is not multipart");
    }

    if (!Number.isInteger(partNumber) || partNumber < 1 || (upload.partCount && partNumber > upload.partCount)) {
      throw new BadRequestException("Invalid multipart part number");
    }

    const result = await this.uploadsRepository.completeUploadPart(upload.id, partNumber, {
      etag: body.etag,
      sizeBytes: body.size_bytes
    });

    return {
      upload_id: upload.id,
      part_number: partNumber,
      status: "uploaded",
      uploaded_part_count: result.uploadedPartCount
    };
  }

  async completeUpload(uploadId: string, body: UploadCompleteRequestDto): Promise<UploadCompleteResponse> {
    const upload = await this.uploadsRepository.getUploadById(uploadId);
    if (!upload) {
      throw new NotFoundException("Upload not found");
    }

    const existingDocument = await this.documentsRepository.getDocumentByUploadId(uploadId);
    if (existingDocument) {
      const latestJob = await this.jobsRepository.getLatestJobByDocumentId(existingDocument.id);
      return {
        upload_id: uploadId,
        document_id: existingDocument.id,
        job_id: latestJob?.id ?? "",
        upload_status: this.toDocumentUploadStatus(existingDocument.uploadStatus),
        parse_status: existingDocument.parseStatus,
        diagnosis_code: this.toDiagnosisCode(existingDocument.diagnosisCode)
      };
    }

    if (upload.status === "expired") {
      throw new BadRequestException("Upload has expired");
    }

    if (upload.status === "failed") {
      throw new BadRequestException("Upload has failed and cannot be completed");
    }

    try {
      await this.uploadsRepository.updateUpload(upload.id, {
        status: "verifying",
        completedByClientAt: new Date()
      });

      if (upload.uploadMode === "multipart") {
        const parts = await this.resolveMultipartParts(upload.id, upload.partCount, body.parts);

        if (!upload.providerUploadId) {
          throw new Error("Multipart upload id is missing");
        }

        await this.storageService.completeMultipartUpload({
          objectKey: upload.objectKey,
          uploadId: upload.providerUploadId,
          parts
        });
      }

      const metadata = await this.storageService.getObjectMetadata(upload.objectKey);
      if (metadata.size !== null && metadata.size !== upload.fileSize) {
        throw new Error(
          `Uploaded object size mismatch: expected ${upload.fileSize} bytes but received ${metadata.size} bytes`
        );
      }
    } catch (error) {
      const code: DiagnosisCode =
        upload.uploadMode === "multipart" ? "upload_complete_invalid_parts" : "object_missing_on_complete";
      await this.uploadsRepository.updateUpload(upload.id, {
        status: "failed",
        errorCode: code,
        errorMessage: error instanceof Error ? error.message : "Upload verification failed"
      });

      this.throwUploadError(
        code,
        code === "object_missing_on_complete"
          ? "上传完成后未在对象存储中找到目标文件。"
          : "上传完成时分片信息无效或对象合并失败。",
        true,
        { upload_id: upload.id }
      );
    }

    const result = await this.database.db.transaction(async (tx) => {
      const latestDocument = await this.documentsRepository.getDocumentByUploadId(uploadId, tx);
      if (latestDocument) {
        const latestJob = await this.jobsRepository.getLatestJobByDocumentId(latestDocument.id, tx);
        return {
          upload_id: upload.id,
          document_id: latestDocument.id,
          job_id: latestJob?.id ?? "",
          upload_status: this.toDocumentUploadStatus(latestDocument.uploadStatus),
          parse_status: latestDocument.parseStatus,
          diagnosis_code: this.toDiagnosisCode(latestDocument.diagnosisCode)
        };
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
          parseStatus: "pending",
          uploadStatus: "uploaded",
          diagnosisCode: null,
          diagnosisSummary: null,
          uploadId: upload.id
        },
        tx
      );

      await this.documentsRepository.replaceDocumentTags(
        document.id,
        tagRows.map((tag) => tag.id),
        tx
      );
      await this.uploadsRepository.updateUpload(
        upload.id,
        {
          checksumSha256: body.checksum_sha256,
          status: "uploaded",
          verifiedAt: new Date(),
          completedAt: new Date(),
          errorCode: null,
          errorMessage: null
        },
        tx
      );

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
        upload_id: upload.id,
        document_id: document.id,
        job_id: job.id,
        upload_status: "uploaded" as const,
        parse_status: document.parseStatus,
        diagnosis_code: this.toDiagnosisCode(document.diagnosisCode)
      };
    });

    try {
      const queueJobId = await this.queueService.enqueueParseDocument(result.document_id, uploadId);
      await this.jobsRepository.updateJob(result.job_id, {
        queueJobId
      });
    } catch (error) {
      await this.jobsRepository.updateJob(result.job_id, {
        status: "failed",
        errorCode: "queue_backlog",
        errorMessage: error instanceof Error ? error.message : "Failed to enqueue parse job",
        diagnosisCode: "queue_backlog",
        finishedAt: new Date()
      });
      await this.documentsRepository.updateDocumentProjection(result.document_id, {
        parseStatus: "failed",
        parseErrorMessage: "Failed to enqueue parse job",
        diagnosisCode: "queue_backlog",
        diagnosisSummary: "解析任务未能入队，请稍后重试。"
      });
      throw new BadRequestException("Failed to enqueue parse job");
    }

    return result;
  }

  private async resolveMultipartParts(
    uploadId: string,
    partCount: number | null,
    requestParts?: CompletedUploadPart[]
  ) {
    if (!partCount) {
      throw new Error("Multipart part count is missing");
    }

    const storedParts = await this.uploadsRepository.listUploadParts(uploadId);
    const normalizedParts = requestParts?.length
      ? requestParts.map((part) => ({
          partNumber: part.part_number,
          etag: part.etag
        }))
      : storedParts.map((part) => ({
          partNumber: part.partNumber,
          etag: part.etag
        }));

    const parts = normalizedParts
      .filter((part): part is { partNumber: number; etag: string } => Boolean(part.partNumber && part.etag))
      .sort((left, right) => left.partNumber - right.partNumber);

    if (parts.length !== partCount) {
      throw new Error(`Expected ${partCount} uploaded parts but received ${parts.length}`);
    }

    for (let index = 0; index < parts.length; index += 1) {
      if (parts[index].partNumber !== index + 1) {
        throw new Error("Multipart part list is incomplete");
      }
    }

    return parts;
  }

  private throwUploadError(
    code: DiagnosisCode,
    message: string,
    retryable: boolean,
    context: Record<string, number | string>
  ): never {
    throw new BadRequestException({
      code,
      message,
      retryable,
      context
    });
  }

  private toDiagnosisCode(value: string | null): DiagnosisCode | null {
    return value ? (value as DiagnosisCode) : null;
  }

  private toUploadSessionStatus(value: string): UploadSessionStatus {
    return value === "draft" ? "initiated" : (value as UploadSessionStatus);
  }

  private toDocumentUploadStatus(value: string | null): DocumentUploadStatus {
    if (!value || value === "expired") {
      return "failed";
    }

    return value as DocumentUploadStatus;
  }
}
