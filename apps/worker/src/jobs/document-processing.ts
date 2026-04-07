import { normalizeWhitespace, createContentPreview, buildSearchText, inferTextSupport } from "../common/document-utils";
import type { WorkerRepository } from "../database/repository";
import type { Logger } from "../logging/logger";
import { fetchAndExtractLinkDocument, LinkFetchError, type ParsedLinkDocument } from "./link-parser";
import { parsePdfDocument, type ParsedPdfDocument } from "./pdf-parser";
import { DOCUMENT_PROCESSING_JOB_NAMES, type DocumentProcessingJobName } from "../queue/constants";
import type { WorkerStorageService } from "../storage/storage";

export interface DocumentProcessingJobData {
  documentId: string;
  uploadId?: string;
  requestedBy?: string;
}

export interface DocumentProcessingJobResult {
  documentId: string;
  status: "success" | "failed" | "skipped";
  reason?: string;
}

type JobContext = {
  name: DocumentProcessingJobName;
  id: string;
  data: DocumentProcessingJobData;
  attemptsMade: number;
};

export interface DocumentProcessingDependencies {
  repository: WorkerRepository;
  storage: WorkerStorageService;
  logger: Logger;
  parsePdf?: (bytes: Uint8Array) => Promise<ParsedPdfDocument>;
  fetchLink?: (url: string) => Promise<ParsedLinkDocument>;
}

async function processDocument(context: JobContext, deps: DocumentProcessingDependencies): Promise<DocumentProcessingJobResult> {
  const { repository, storage, logger } = deps;
  const parsePdf = deps.parsePdf ?? parsePdfDocument;
  const jobType =
    context.name === DOCUMENT_PROCESSING_JOB_NAMES.reparseDocument ? "reparse_document" : "parse_document";
  const job = await repository.findJob(context.id, context.data.documentId, jobType);

  if (!job) {
    logger.warn("job record not found", {
      queueJobId: context.id,
      documentId: context.data.documentId,
      jobType
    });
    return {
      documentId: context.data.documentId,
      status: "skipped",
      reason: "job record not found"
    };
  }

  const document = await repository.findDocument(context.data.documentId);
  if (!document) {
    await repository.markJobFailed(job.id, "document not found", null, context.attemptsMade >= 2);
    return {
      documentId: context.data.documentId,
      status: "failed",
      reason: "document not found"
    };
  }

  await repository.markJobRunning(job.id, context.id);
  await repository.markDocumentProcessing(document.id);

  try {
    const source = resolveDocumentSource(document, context.name);
    if (source.kind === "inline") {
      const tagNames = await repository.listDocumentTags(document.id);
      const contentClean = normalizeWhitespace(source.content);
      await repository.markDocumentSuccess(document.id, {
        contentRaw: source.content,
        contentClean,
        contentPreview: createContentPreview(contentClean),
        searchText: buildSearchText({
          title: document.title,
          contentClean,
          tags: tagNames,
          fileName: document.file_name,
          sourceUrl: document.source_url
        })
      });
      await repository.markJobCompleted(job.id);
      return {
        documentId: document.id,
        status: "success"
      };
    }

    const support = inferTextSupport(document.mime_type);
    if (!support.supported) {
      throw new Error(support.reason || "unsupported document source");
    }

    if (document.mime_type === "application/pdf") {
      const pdfBytes = await storage.getObjectBytes(source.objectKey);
      const parsedPdf = await parsePdf(pdfBytes);
      const tagNames = await repository.listDocumentTags(document.id);
      const contentRaw = parsedPdf.text;
      const contentClean = normalizeWhitespace(parsedPdf.text);

      await repository.markDocumentSuccess(document.id, {
        contentRaw,
        contentClean,
        contentPreview: createContentPreview(contentClean),
        searchText: buildSearchText({
          title: document.title,
          contentClean,
          tags: tagNames,
          fileName: document.file_name,
          sourceUrl: document.source_url
        }),
        pageCount: parsedPdf.pageCount,
        parserName: parsedPdf.parserName,
        parserVersion: parsedPdf.parserVersion
      });
      await repository.markJobCompleted(job.id);

      return {
        documentId: document.id,
        status: "success"
      };
    }

    const objectBody = await storage.getObjectBody(source.objectKey);
    const tagNames = await repository.listDocumentTags(document.id);
    const contentRaw = objectBody;
    const contentClean = normalizeWhitespace(objectBody);

    await repository.markDocumentSuccess(document.id, {
      contentRaw,
      contentClean,
      contentPreview: createContentPreview(contentClean),
      searchText: buildSearchText({
        title: document.title,
        contentClean,
        tags: tagNames,
        fileName: document.file_name,
        sourceUrl: document.source_url
      })
    });
    await repository.markJobCompleted(job.id);

    return {
      documentId: document.id,
      status: "success"
    };
  } catch (error) {
    const reason = error instanceof Error ? error.message : "document processing failed";
    const dead = context.attemptsMade >= 2;
    const diagnosisCode = mapDiagnosisCode(reason, document.mime_type);
    await repository.markDocumentFailed(document.id, reason, diagnosisCode);
    await repository.markJobFailed(job.id, reason, diagnosisCode, dead);
    return {
      documentId: document.id,
      status: "failed",
      reason
    };
  }
}

export function mapDiagnosisCode(message: string, mimeType: string | null): string | null {
  const normalized = message.toLowerCase();

  if (mimeType === "application/pdf" || normalized.includes("pdf")) {
    if (
      normalized.includes("cannot transfer object of unsupported type") ||
      normalized.includes("datacloneerror") ||
      normalized.includes("structuredclone")
    ) {
      return "pdf_parse_runtime_error";
    }

    if (normalized.includes("timeout")) {
      return "pdf_parse_timeout";
    }

    if (normalized.includes("empty")) {
      return "pdf_parse_empty_text";
    }

    return "pdf_parse_unsupported";
  }

  return null;
}

async function processLinkFetch(context: JobContext, deps: DocumentProcessingDependencies): Promise<DocumentProcessingJobResult> {
  const { repository, logger } = deps;
  const fetchLink = deps.fetchLink ?? fetchAndExtractLinkDocument;
  const job = await repository.findJob(context.id, context.data.documentId, "fetch_link");

  if (!job) {
    logger.warn("link fetch job record not found", {
      queueJobId: context.id,
      documentId: context.data.documentId,
      jobType: "fetch_link"
    });
    return {
      documentId: context.data.documentId,
      status: "skipped",
      reason: "job record not found"
    };
  }

  const document = await repository.findDocument(context.data.documentId);
  if (!document) {
    await repository.markJobFailed(job.id, "document not found", null, context.attemptsMade >= 2);
    return {
      documentId: context.data.documentId,
      status: "failed",
      reason: "document not found"
    };
  }

  if (!document.source_url) {
    await repository.markDocumentFailed(document.id, "链接地址为空，无法抓取正文。", "link_invalid_url");
    await repository.markJobFailed(job.id, "document source url is empty", "link_invalid_url", context.attemptsMade >= 2);
    return {
      documentId: document.id,
      status: "failed",
      reason: "document source url is empty"
    };
  }

  const fetchRecord = await repository.createSourceFetch(document.id, document.source_url);
  await repository.markJobRunning(job.id, context.id);
  await repository.markDocumentProcessing(document.id);
  await repository.markSourceFetchRunning(fetchRecord.id);
  await repository.createProcessingEvent({
    documentId: document.id,
    eventType: "link_fetch_started",
    stage: "fetch",
    status: "processing",
    summary: "开始抓取链接正文。",
    payload: {
      source_url: document.source_url,
      fetch_id: fetchRecord.id
    }
  });

  try {
    const parsed = await fetchLink(document.source_url);
    const tagNames = await repository.listDocumentTags(document.id);
    const title = document.title === document.source_url ? parsed.title || document.title : document.title;
    const contentClean = normalizeWhitespace(parsed.text);

    await repository.markDocumentSuccess(document.id, {
      title,
      contentRaw: parsed.text,
      contentClean,
      contentPreview: createContentPreview(contentClean),
      searchText: buildSearchText({
        title,
        contentClean,
        tags: tagNames,
        fileName: document.file_name,
        sourceUrl: parsed.canonicalUrl || parsed.sourceUrl
      }),
      mimeType: parsed.contentType,
      parserName: parsed.parserName,
      parserVersion: parsed.parserVersion
    });
    await repository.markSourceFetchSucceeded(fetchRecord.id, {
      contentType: parsed.contentType,
      canonicalUrl: parsed.canonicalUrl,
      titleExtracted: parsed.title
    });
    await repository.createProcessingEvent({
      documentId: document.id,
      eventType: "link_fetch_succeeded",
      stage: "fetch",
      status: "success",
      summary: "链接正文抓取成功，已写入搜索投影。",
      payload: {
        fetch_id: fetchRecord.id,
        canonical_url: parsed.canonicalUrl,
        parser_name: parsed.parserName
      }
    });
    await repository.markJobCompleted(job.id);

    return {
      documentId: document.id,
      status: "success"
    };
  } catch (error) {
    const linkError =
      error instanceof LinkFetchError
        ? error
        : new LinkFetchError(error instanceof Error ? error.message : "link fetch failed", "link_fetch_timeout");
    await repository.markSourceFetchFailed(fetchRecord.id, {
      diagnosisCode: linkError.diagnosisCode,
      errorMessage: linkError.message,
      httpStatus: linkError.httpStatus,
      contentType: linkError.contentType
    });
    await repository.markDocumentFailed(document.id, linkError.message, linkError.diagnosisCode);
    await repository.markJobFailed(job.id, linkError.message, linkError.diagnosisCode, context.attemptsMade >= 2);
    await repository.createProcessingEvent({
      documentId: document.id,
      eventType: "link_fetch_failed",
      stage: "fetch",
      status: "failed",
      diagnosisCode: linkError.diagnosisCode,
      summary: "链接正文抓取失败。",
      payload: {
        fetch_id: fetchRecord.id,
        http_status: linkError.httpStatus
      }
    });
    return {
      documentId: document.id,
      status: "failed",
      reason: linkError.message
    };
  }
}

function resolveDocumentSource(
  document: {
    content_raw: string | null;
    object_key: string | null;
  },
  jobName: DocumentProcessingJobName
):
  | { kind: "inline"; content: string }
  | { kind: "object"; objectKey: string } {
  if (document.content_raw && jobName === DOCUMENT_PROCESSING_JOB_NAMES.reparseDocument) {
    return {
      kind: "inline",
      content: document.content_raw
    };
  }

  if (document.object_key) {
    return {
      kind: "object",
      objectKey: document.object_key
    };
  }

  if (document.content_raw) {
    return {
      kind: "inline",
      content: document.content_raw
    };
  }

  throw new Error("document source is empty");
}

export function createDocumentProcessingHandlers(deps: DocumentProcessingDependencies) {
  return {
    [DOCUMENT_PROCESSING_JOB_NAMES.parseDocument]: (context: JobContext) => processDocument(context, deps),
    [DOCUMENT_PROCESSING_JOB_NAMES.reparseDocument]: (context: JobContext) => processDocument(context, deps),
    [DOCUMENT_PROCESSING_JOB_NAMES.runOcr]: async (context: JobContext) => ({
      documentId: context.data.documentId,
      status: "skipped" as const,
      reason: "ocr pipeline not wired yet"
    }),
    [DOCUMENT_PROCESSING_JOB_NAMES.fetchLink]: (context: JobContext) => processLinkFetch(context, deps),
    [DOCUMENT_PROCESSING_JOB_NAMES.refreshSearchProjection]: async (context: JobContext) => ({
      documentId: context.data.documentId,
      status: "skipped" as const,
      reason: "search projection refresh not wired yet"
    }),
    [DOCUMENT_PROCESSING_JOB_NAMES.rebuildSearchProjection]: async (context: JobContext) => ({
      documentId: context.data.documentId,
      status: "skipped" as const,
      reason: "search projection rebuild not wired yet"
    })
  } satisfies Record<DocumentProcessingJobName, (context: JobContext) => Promise<DocumentProcessingJobResult>>;
}
