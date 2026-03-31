import { BadRequestException, Injectable, NotFoundException } from "@nestjs/common";
import { randomUUID } from "node:crypto";
import type {
  CreateTextDocumentResponse,
  DocumentDetail,
  DocumentListResponse,
  DocumentSummary,
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
import { TagsRepository } from "../tags/tags.repository";
import { CreateTextDocumentRequestDto, ListDocumentsQueryDto, UpdateDocumentTagsRequestDto } from "./documents.dto";
import { DocumentsRepository } from "./documents.repository";

type DocumentRow = typeof documents.$inferSelect;

@Injectable()
export class DocumentsService {
  constructor(
    private readonly database: DatabaseService,
    private readonly documentsRepository: DocumentsRepository,
    private readonly tagsRepository: TagsRepository,
    private readonly jobsRepository: JobsRepository
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

      return {
        id: document.id,
        parse_status: document.parseStatus
      };
    });
  }

  async listDocuments(query: ListDocumentsQueryDto): Promise<DocumentListResponse> {
    const parseStatuses = parseCommaSeparated(query.parse_status).filter(
      (value): value is ParseStatus => ["pending", "processing", "success", "failed"].includes(value)
    );
    const tags = parseCommaSeparated(query.tags);
    const result = await this.documentsRepository.listDocuments({
      q: query.q,
      sourceType: query.source_type,
      parseStatuses,
      tags,
      dateFrom: query.date_from,
      dateTo: query.date_to,
      page: query.page,
      pageSize: query.page_size
    });
    const tagMap = await this.documentsRepository.loadTagsByDocumentIds(result.items.map((item) => item.id));

    return {
      items: result.items.map((document) => this.toDocumentSummary(document, tagMap.get(document.id) || [])),
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
    return this.toDocumentDetail(document, tagMap.get(document.id) || []);
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

      return this.toDocumentDetail(updated, tagRows.map((tag) => tag.name));
    });
  }

  async retryDocument(documentId: string): Promise<RetryDocumentResponse> {
    return this.database.db.transaction(async (tx) => {
      const document = await this.documentsRepository.getDocumentById(documentId, tx);
      if (!document) {
        throw new NotFoundException("Document not found");
      }

      if (!["failed", "success"].includes(document.parseStatus)) {
        throw new BadRequestException("Only failed or successful documents can be retried");
      }

      const nextAttempt = await this.jobsRepository.getNextAttempt(documentId, tx);
      const job = await this.jobsRepository.createJob(
        {
          id: randomUUID(),
          documentId,
          queueJobId: null,
          jobType: "reparse_document",
          status: "queued",
          attempt: nextAttempt
        },
        tx
      );

      await this.documentsRepository.updateDocumentProjection(
        documentId,
        {
          parseStatus: "pending",
          parseErrorMessage: null
        },
        tx
      );

      return {
        document_id: documentId,
        job_id: job.id,
        parse_status: "pending"
      };
    });
  }

  private toDocumentSummary(document: DocumentRow, tagNames: string[]): DocumentSummary {
    return {
      id: document.id,
      title: document.title,
      content_preview: document.contentPreview || "",
      tags: tagNames,
      source_type: document.sourceType,
      source_origin: document.sourceOrigin,
      file_name: document.fileName,
      parse_status: document.parseStatus,
      imported_at: toIsoString(document.importedAt)
    };
  }

  private toDocumentDetail(document: DocumentRow, tagNames: string[]): DocumentDetail {
    return {
      ...this.toDocumentSummary(document, tagNames),
      content_raw: document.contentRaw,
      content_clean: document.contentClean,
      source_url: document.sourceUrl,
      mime_type: document.mimeType,
      parse_error_message: document.parseErrorMessage,
      created_at: toIsoString(document.createdAt)
    };
  }
}
