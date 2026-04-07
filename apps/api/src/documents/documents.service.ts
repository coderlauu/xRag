import { BadRequestException, Injectable, NotFoundException } from "@nestjs/common";
import { randomUUID } from "node:crypto";
import type {
  CreateLinkDocumentResponse,
  CreateTextDocumentResponse,
  DiagnosisCode,
  DocumentDetail,
  DocumentJobType,
  DocumentLatestJobInfo,
  DocumentListResponse,
  DocumentSummary,
  DocumentTimelineResponse,
  DocumentUploadInfo,
  DocumentUploadStatus,
  OcrStatus,
  UploadSessionStatus,
  ParseStatus,
  RetryDocumentResponse
} from "@xrag/shared-types";
import {
  buildSearchText,
  createContentPreview,
  normalizeWhitespace,
  parseCommaSeparated,
  toIsoString
} from "../common/document-utils";
import { DatabaseService } from "../database/database.service";
import { documents } from "../database/schema";
import { JobsRepository } from "../jobs/jobs.repository";
import { QueueService } from "../queue/queue.service";
import { TagsRepository } from "../tags/tags.repository";
import { UploadsRepository } from "../uploads/uploads.repository";
import {
  CreateLinkDocumentRequestDto,
  CreateTextDocumentRequestDto,
  ListDocumentsQueryDto,
  UpdateDocumentTagsRequestDto
} from "./documents.dto";
import { DocumentsRepository } from "./documents.repository";

type DocumentRow = typeof documents.$inferSelect;

@Injectable()
export class DocumentsService {
  constructor(
    private readonly database: DatabaseService,
    private readonly documentsRepository: DocumentsRepository,
    private readonly tagsRepository: TagsRepository,
    private readonly jobsRepository: JobsRepository,
    private readonly uploadsRepository: UploadsRepository,
    private readonly queueService: QueueService
  ) {}

  async createTextDocument(body: CreateTextDocumentRequestDto): Promise<CreateTextDocumentResponse> {
    return this.database.db.transaction(async (tx) => {
      const title = normalizeWhitespace(body.title);
      const contentRaw = body.content.trim();
      const contentClean = normalizeWhitespace(body.content);
      const tagRows = await this.tagsRepository.upsertTags(body.tags, tx);
      const document = await this.documentsRepository.createDocument(
        {
          id: randomUUID(),
          title,
          contentRaw,
          contentClean,
          contentPreview: createContentPreview(contentClean),
          searchText: buildSearchText({
            title,
            contentClean,
            tags: tagRows.map((tag) => tag.name),
            fileName: null,
            sourceUrl: null
          }),
          sourceType: "text",
          sourceOrigin: "manual_input",
          parseStatus: "success"
        },
        tx
      );

      await this.documentsRepository.replaceDocumentTags(
        document.id,
        tagRows.map((tag) => tag.id),
        tx
      );

      await this.documentsRepository.createProcessingEvent(
        {
          id: randomUUID(),
          documentId: document.id,
          eventType: "manual_text_created",
          stage: "parse",
          status: "success",
          diagnosisCode: null,
          summary: "手动文本已入库，无需额外解析。",
          payload: null
        },
        tx
      );

      return {
        id: document.id,
        parse_status: document.parseStatus
      };
    });
  }

  async createLinkDocument(body: CreateLinkDocumentRequestDto): Promise<CreateLinkDocumentResponse> {
    const normalizedUrl = this.normalizeSourceUrl(body.source_url);

    const createResult = await this.database.db.transaction(async (tx) => {
      const tagRows = await this.tagsRepository.upsertTags(body.tags, tx);
      const title = normalizeWhitespace(body.title || normalizedUrl);
      const document = await this.documentsRepository.createDocument(
        {
          id: randomUUID(),
          title,
          contentRaw: null,
          contentClean: null,
          contentPreview: "链接正文待抓取",
          searchText: buildSearchText({
            title,
            contentClean: null,
            tags: tagRows.map((tag) => tag.name),
            fileName: null,
            sourceUrl: normalizedUrl
          }),
          sourceType: "link",
          sourceOrigin: "link",
          sourceUrl: normalizedUrl,
          parseStatus: "pending",
          ocrStatus: "not_required"
        },
        tx
      );

      await this.documentsRepository.replaceDocumentTags(
        document.id,
        tagRows.map((tag) => tag.id),
        tx
      );

      const nextAttempt = await this.jobsRepository.getNextAttempt(document.id, tx);
      const job = await this.jobsRepository.createJob(
        {
          id: randomUUID(),
          documentId: document.id,
          queueJobId: null,
          jobType: "fetch_link",
          status: "queued",
          attempt: nextAttempt,
          diagnosisCode: null,
          incidentRef: null,
          runtimeMs: null
        },
        tx
      );

      await this.documentsRepository.createProcessingEvent(
        {
          id: randomUUID(),
          documentId: document.id,
          eventType: "link_fetch_queued",
          stage: "fetch",
          status: "pending",
          diagnosisCode: null,
          summary: "链接文档已创建，等待抓取正文。",
          payload: {
            source_url: normalizedUrl,
            job_id: job.id
          }
        },
        tx
      );

      return {
        id: document.id,
        jobId: job.id
      };
    });

    try {
      const queueJobId = await this.queueService.enqueueFetchLink(createResult.id);
      await this.jobsRepository.updateJob(createResult.jobId, {
        queueJobId
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : "Failed to enqueue link fetch job";
      await this.jobsRepository.updateJob(createResult.jobId, {
        status: "failed",
        errorCode: "queue_backlog",
        errorMessage: message,
        diagnosisCode: "queue_backlog",
        finishedAt: new Date()
      });
      await this.documentsRepository.updateDocumentProjection(createResult.id, {
        parseStatus: "failed",
        diagnosisCode: "queue_backlog",
        diagnosisSummary: "链接抓取任务未能入队，请稍后重试。",
        parseErrorMessage: message
      });
      await this.documentsRepository.createProcessingEvent({
        id: randomUUID(),
        documentId: createResult.id,
        eventType: "link_fetch_enqueue_failed",
        stage: "fetch",
        status: "failed",
        diagnosisCode: "queue_backlog",
        summary: "链接抓取任务入队失败。",
        payload: null
      });
      throw new BadRequestException("Failed to enqueue link fetch job");
    }

    return {
      id: createResult.id,
      parse_status: "pending",
      diagnosis_code: null
    };
  }

  async listDocuments(query: ListDocumentsQueryDto): Promise<DocumentListResponse> {
    const ocrStatuses = parseCommaSeparated(query.ocr_status).filter(
      (value): value is OcrStatus => ["not_required", "queued", "processing", "success", "failed"].includes(value)
    );
    const parseStatuses = parseCommaSeparated(query.parse_status).filter(
      (value): value is ParseStatus => ["pending", "processing", "success", "failed"].includes(value)
    );
    const uploadStatuses = parseCommaSeparated(query.upload_status).filter(
      (value): value is DocumentUploadStatus =>
        ["draft", "initiated", "uploading", "verifying", "uploaded", "failed"].includes(value)
    );
    const tags = parseCommaSeparated(query.tags);
    const result = await this.documentsRepository.listDocuments({
      q: query.q,
      sourceType: query.source_type,
      ocrStatuses,
      parseStatuses,
      uploadStatuses,
      diagnosisCode: query.diagnosis_code,
      tags,
      dateFrom: query.date_from,
      dateTo: query.date_to,
      page: query.page,
      pageSize: query.page_size
    });
    const tagMap = await this.documentsRepository.loadTagsByDocumentIds(result.items.map((item) => item.id));
    const latestJobs = await this.jobsRepository.listLatestJobsByDocumentIds(result.items.map((item) => item.id));
    const latestJobMap = new Map(latestJobs.map((job) => [job.documentId, job]));

    return {
      items: result.items.map((document) =>
        this.toDocumentSummary(document, tagMap.get(document.id) || [], latestJobMap.get(document.id) || null, query.q)
      ),
      page: query.page,
      page_size: query.page_size,
      total: result.total
    };
  }

  async getDocument(documentId: string): Promise<DocumentDetail> {
    const document = await this.documentsRepository.getDocumentById(documentId);
    if (!document) {
      throw new NotFoundException("Document not found");
    }

    const tagMap = await this.documentsRepository.loadTagsByDocumentIds([document.id]);
    const latestJob = await this.jobsRepository.getLatestJobByDocumentId(document.id);
    const upload = document.uploadId ? await this.uploadsRepository.getUploadById(document.uploadId) : null;
    return this.toDocumentDetail(document, tagMap.get(document.id) || [], latestJob, upload);
  }

  async getDocumentTimeline(documentId: string): Promise<DocumentTimelineResponse> {
    const document = await this.documentsRepository.getDocumentById(documentId);
    if (!document) {
      throw new NotFoundException("Document not found");
    }

    const items = await this.documentsRepository.listProcessingEventsByDocumentId(documentId);
    return {
      document_id: documentId,
      items: items.map((item) => ({
        event_type: item.eventType,
        stage: item.stage,
        status: item.status,
        diagnosis_code: this.toDiagnosisCode(item.diagnosisCode),
        summary: item.summary,
        created_at: toIsoString(item.createdAt)
      }))
    };
  }

  async updateDocumentTags(documentId: string, body: UpdateDocumentTagsRequestDto): Promise<DocumentDetail> {
    return this.database.db.transaction(async (tx) => {
      const document = await this.documentsRepository.getDocumentById(documentId, tx);
      if (!document) {
        throw new NotFoundException("Document not found");
      }

      const tagRows = await this.tagsRepository.upsertTags(body.tags, tx);
      await this.documentsRepository.replaceDocumentTags(
        document.id,
        tagRows.map((tag) => tag.id),
        tx
      );

      const updated = await this.documentsRepository.updateDocumentProjection(
        document.id,
        {
          searchText: buildSearchText({
            title: document.title,
            contentClean: document.contentClean,
            tags: tagRows.map((tag) => tag.name),
            fileName: document.fileName,
            sourceUrl: document.sourceUrl
          })
        },
        tx
      );

      const latestJob = await this.jobsRepository.getLatestJobByDocumentId(document.id, tx);
      const upload = updated.uploadId ? await this.uploadsRepository.getUploadById(updated.uploadId, tx) : null;

      return this.toDocumentDetail(updated, tagRows.map((tag) => tag.name), latestJob, upload);
    });
  }

  async retryDocument(documentId: string): Promise<RetryDocumentResponse> {
    const retryResult = await this.database.db.transaction(async (tx) => {
      const document = await this.documentsRepository.getDocumentById(documentId, tx);
      if (!document) {
        throw new NotFoundException("Document not found");
      }

      if (!["failed", "success"].includes(document.parseStatus)) {
        throw new BadRequestException("Only failed or successful documents can be retried");
      }

      const nextAttempt = await this.jobsRepository.getNextAttempt(documentId, tx);
      const retryJobType = this.resolveRetryJobType(document);
      const job = await this.jobsRepository.createJob(
        {
          id: randomUUID(),
          documentId,
          queueJobId: null,
          jobType: retryJobType,
          status: "queued",
          attempt: nextAttempt,
          diagnosisCode: null,
          incidentRef: null,
          runtimeMs: null
        },
        tx
      );

      await this.documentsRepository.createProcessingEvent(
        {
          id: randomUUID(),
          documentId,
          eventType:
            retryJobType === "fetch_link"
              ? "link_retry_queued"
              : retryJobType === "run_ocr"
                ? "ocr_retry_queued"
                : "reparse_queued",
          stage: retryJobType === "fetch_link" ? "fetch" : retryJobType === "run_ocr" ? "ocr" : "parse",
          status: "pending",
          diagnosisCode: null,
          summary:
            retryJobType === "fetch_link"
              ? "已创建链接抓取重试任务。"
              : retryJobType === "run_ocr"
                ? "已创建 OCR 重试任务。"
                : "已创建解析重试任务。",
          payload: {
            job_id: job.id
          }
        },
        tx
      );

      await this.documentsRepository.updateDocumentProjection(
        documentId,
        {
          parseStatus: "pending",
          parseErrorMessage: null,
          diagnosisCode: null,
          diagnosisSummary: null
        },
        tx
      );

      return {
        document_id: documentId,
        job_id: job.id,
        parse_status: "pending" as const,
        diagnosis_code: null,
        retry_job_type: retryJobType
      };
    });

    try {
      const queueJobId =
        retryResult.retry_job_type === "fetch_link"
          ? await this.queueService.enqueueFetchLink(documentId)
          : retryResult.retry_job_type === "run_ocr"
            ? await this.queueService.enqueueRunOcr(documentId)
          : await this.queueService.enqueueReparseDocument(documentId);
      await this.jobsRepository.updateJob(retryResult.job_id, {
        queueJobId
      });
    } catch (error) {
      await this.jobsRepository.updateJob(retryResult.job_id, {
        status: "failed",
        errorCode: "queue_backlog",
        errorMessage: error instanceof Error ? error.message : "Failed to enqueue reparse job",
        diagnosisCode: "queue_backlog",
        finishedAt: new Date()
      });
      await this.documentsRepository.updateDocumentProjection(documentId, {
        parseStatus: "failed",
        parseErrorMessage: "Failed to enqueue reparse job",
        diagnosisCode: "queue_backlog",
        diagnosisSummary: "重试任务未能入队，请稍后重试。"
      });
      throw new BadRequestException("Failed to enqueue reparse job");
    }

    return {
      document_id: retryResult.document_id,
      job_id: retryResult.job_id,
      parse_status: retryResult.parse_status,
      diagnosis_code: retryResult.diagnosis_code
    };
  }

  private toDocumentSummary(
    document: DocumentRow,
    tagNames: string[],
    latestJob: {
      status: DocumentLatestJobInfo["status"];
    } | null,
    query?: string
  ): DocumentSummary {
    const searchSignals = this.buildSearchSignals(document, query);

    return {
      id: document.id,
      title: document.title,
      content_preview: document.contentPreview || "",
      tags: tagNames,
      source_type: document.sourceType,
      source_origin: document.sourceOrigin,
      source_url: document.sourceUrl,
      file_name: document.fileName,
      parse_status: document.parseStatus,
      ocr_status: document.ocrStatus,
      upload_status: this.toDocumentUploadStatus(document.uploadStatus),
      diagnosis_code: this.toDiagnosisCode(document.diagnosisCode),
      diagnosis_summary: document.diagnosisSummary,
      match_explanation: searchSignals.matchExplanation,
      ranking_hint: searchSignals.rankingHint,
      matched_fields: searchSignals.matchedFields,
      latest_job_status: latestJob?.status ?? null,
      page_count: document.pageCount,
      parser_name: document.parserName,
      imported_at: toIsoString(document.importedAt)
    };
  }

  private toDocumentDetail(
    document: DocumentRow,
    tagNames: string[],
    latestJobRow: {
      id: string;
      status: DocumentLatestJobInfo["status"];
      diagnosisCode: string | null;
      incidentRef: string | null;
      runtimeMs: number | null;
      finishedAt: Date | null;
    } | null,
    uploadRow: {
      id: string;
      uploadMode: DocumentUploadInfo["upload_mode"];
      status: string;
      partCount: number | null;
      uploadedPartCount: number;
      verifiedAt: Date | null;
    } | null
  ): DocumentDetail {
    const searchSignals = this.buildSearchSignals(document);
    const latestJob = latestJobRow
      ? {
          id: latestJobRow.id,
          status: latestJobRow.status,
          diagnosis_code: this.toDiagnosisCode(latestJobRow.diagnosisCode),
          finished_at: latestJobRow.finishedAt ? toIsoString(latestJobRow.finishedAt) : null
        }
      : null;

    const upload = uploadRow
      ? {
          id: uploadRow.id,
          upload_mode: uploadRow.uploadMode,
          status: this.toUploadSessionStatus(uploadRow.status),
          part_count: uploadRow.partCount,
          uploaded_part_count: uploadRow.uploadedPartCount,
          verified_at: uploadRow.verifiedAt ? toIsoString(uploadRow.verifiedAt) : null
        }
      : null;

    return {
      ...this.toDocumentSummary(document, tagNames, latestJob),
      content_raw: document.contentRaw,
      content_clean: document.contentClean,
      source_url: document.sourceUrl,
      mime_type: document.mimeType,
      parse_error_message: document.parseErrorMessage,
      ocr_engine: document.ocrEngine,
      ocr_language: document.ocrLanguage,
      match_explanation: searchSignals.matchExplanation,
      ranking_hint: searchSignals.rankingHint,
      matched_fields: searchSignals.matchedFields,
      upload,
      latest_job: latestJob,
      last_incident_ref: document.lastIncidentRef,
      page_count: document.pageCount,
      parser_name: document.parserName,
      parser_version: document.parserVersion,
      created_at: toIsoString(document.createdAt)
    };
  }

  private toDiagnosisCode(value: string | null): DiagnosisCode | null {
    if (!value) {
      return null;
    }

    return value as DiagnosisCode;
  }

  private toDocumentUploadStatus(value: string | null): DocumentUploadStatus | null {
    if (!value) {
      return null;
    }

    return value === "expired" ? "failed" : (value as DocumentUploadStatus);
  }

  private toUploadSessionStatus(value: string): UploadSessionStatus {
    return value === "draft" ? "initiated" : (value as UploadSessionStatus);
  }

  private normalizeSourceUrl(rawUrl: string): string {
    try {
      return new URL(rawUrl.trim()).toString();
    } catch {
      throw new BadRequestException("Invalid source url");
    }
  }

  private resolveRetryJobType(document: Pick<DocumentRow, "sourceType" | "ocrStatus">): DocumentJobType {
    if (document.sourceType === "link") {
      return "fetch_link";
    }

    if (document.ocrStatus === "failed") {
      return "run_ocr";
    }

    return "reparse_document";
  }

  private buildSearchSignals(document: DocumentRow, query?: string | null) {
    const storedMatchedFields = document.matchedFields && document.matchedFields.length > 0 ? document.matchedFields : null;
    const storedMatchExplanation = document.matchExplanation?.trim() || null;
    const storedRankingHint = document.rankingHint?.trim() || null;

    if (storedMatchedFields || storedMatchExplanation || storedRankingHint) {
      return {
        matchedFields: storedMatchedFields,
        matchExplanation: storedMatchExplanation,
        rankingHint: storedRankingHint
      };
    }

    const normalizedQuery = query?.trim().toLowerCase() || "";
    const matchedFields: string[] = [];
    const title = document.title.toLowerCase();
    const content = document.contentClean?.toLowerCase() || "";

    if (normalizedQuery) {
      if (title.includes(normalizedQuery)) {
        matchedFields.push("title");
      }

      if (content.includes(normalizedQuery)) {
        if (document.sourceType === "link") {
          matchedFields.push("link_body");
        } else if (document.ocrStatus === "success") {
          matchedFields.push("ocr_text");
        } else {
          matchedFields.push("body");
        }
      }
    } else {
      matchedFields.push("title");
      if (document.sourceType === "link" && content) {
        matchedFields.push("link_body");
      } else if (document.ocrStatus === "success" && content) {
        matchedFields.push("ocr_text");
      } else if (content) {
        matchedFields.push("body");
      }
    }

    const uniqueFields = [...new Set(matchedFields)];

    if (document.parseStatus === "failed" && document.diagnosisCode) {
      return {
        matchedFields: uniqueFields.length > 0 ? uniqueFields : null,
        matchExplanation: `当前文档停留在「${this.toDiagnosisSummary(document.diagnosisCode)}」阶段，因此结果主要用于排查，不保证正文检索完整。`,
        rankingHint: "失败文档保留在结果中，便于定位阻塞环节和后续重试。"
      };
    }

    return {
      matchedFields: uniqueFields.length > 0 ? uniqueFields : null,
      matchExplanation: this.buildMatchExplanation(uniqueFields, normalizedQuery),
      rankingHint: this.buildRankingHint(uniqueFields, document)
    };
  }

  private buildMatchExplanation(fields: string[], query: string) {
    if (fields.length === 0) {
      return query ? `关键词“${query}”尚未形成稳定命中解释。` : "当前文档暂无可展示的命中解释。";
    }

    const labels = fields.map((field) => this.matchFieldLabel(field));
    return query
      ? `关键词“${query}”命中了${labels.join("、")}。`
      : `当前文档的可检索信号主要来自${labels.join("、")}。`;
  }

  private buildRankingHint(fields: string[], document: DocumentRow) {
    const hints: string[] = [];

    if (fields.includes("title")) {
      hints.push("标题命中");
    }

    if (fields.includes("ocr_text")) {
      hints.push("OCR 正文命中");
    } else if (fields.includes("link_body")) {
      hints.push("链接正文命中");
    } else if (fields.includes("body")) {
      hints.push("正文命中");
    }

    if (document.importedAt) {
      hints.push("最近导入加权");
    }

    return hints.length > 0 ? hints.join(" + ") : "当前暂无稳定排序提示。";
  }

  private matchFieldLabel(field: string) {
    switch (field) {
      case "title":
        return "标题";
      case "ocr_text":
        return "OCR 正文";
      case "link_body":
        return "链接正文";
      case "body":
        return "正文";
      default:
        return field;
    }
  }

  private toDiagnosisSummary(code: string) {
    switch (code) {
      case "ocr_runtime_error":
        return "OCR 运行时异常";
      case "ocr_timeout":
        return "OCR 处理超时";
      case "ocr_no_text_detected":
        return "OCR 未识别到有效文本";
      case "link_fetch_timeout":
        return "链接抓取超时";
      case "link_fetch_blocked":
        return "链接抓取被阻止";
      case "link_extract_empty":
        return "链接正文提取为空";
      case "search_projection_stale":
        return "搜索投影需要刷新";
      case "queue_backlog":
        return "任务入队失败";
      default:
        return code;
    }
  }
}
