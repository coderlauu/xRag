import { randomUUID } from "node:crypto";
import { normalizeWhitespace, createContentPreview, buildSearchText, inferTextSupport } from "../common/document-utils";
import type { WorkerRepository } from "../database/repository";
import type { Logger } from "../logging/logger";
import { fetchAndExtractLinkDocument, LinkFetchError, type ParsedLinkDocument } from "./link-parser";
import { runPdfOcr, type ParsedOcrDocument } from "./ocr-parser";
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
  runOcr?: (bytes: Uint8Array) => Promise<ParsedOcrDocument>;
  fetchLink?: (url: string) => Promise<ParsedLinkDocument>;
  enqueueOcr?: (documentId: string, uploadId?: string) => Promise<string>;
  enqueueChunkDocument: (documentId: string, jobId: string) => Promise<string>;
}

async function processDocument(context: JobContext, deps: DocumentProcessingDependencies): Promise<DocumentProcessingJobResult> {
  const { repository, storage, logger } = deps;
  const parsePdf = deps.parsePdf ?? parsePdfDocument;
  const enqueueOcr = deps.enqueueOcr;
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
      await queueIndexingForDocument(document.id, deps, {
        summary: "正文解析完成，已排入问答索引队列。"
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
      let parsedPdf: ParsedPdfDocument;

      try {
        parsedPdf = await parsePdf(pdfBytes);
      } catch (error) {
        const reason = error instanceof Error ? error.message : "document processing failed";
        if (shouldQueueOcr(reason)) {
          return queueOcrForDocument(context, deps, {
            document,
            jobId: job.id,
            uploadId: context.data.uploadId
          });
        }

        throw error;
      }

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
      await queueIndexingForDocument(document.id, deps, {
        summary: "PDF 解析完成，已排入问答索引队列。"
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
    await queueIndexingForDocument(document.id, deps, {
      summary: "文档解析完成，已排入问答索引队列。"
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

export function mapOcrDiagnosisCode(message: string): string {
  const normalized = message.toLowerCase();

  if (normalized.includes("timeout")) {
    return "ocr_timeout";
  }

  if (normalized.includes("empty")) {
    return "ocr_no_text_detected";
  }

  return "ocr_runtime_error";
}

function shouldQueueOcr(message: string): boolean {
  return message.toLowerCase().includes("empty text");
}

async function queueOcrForDocument(
  context: JobContext,
  deps: DocumentProcessingDependencies,
  values: {
    document: WorkerDocumentRecordLike;
    jobId: string;
    uploadId?: string;
  }
): Promise<DocumentProcessingJobResult> {
  const { repository, enqueueOcr } = deps;
  if (!enqueueOcr) {
    throw new Error("ocr queue producer is not configured");
  }

  const nextAttempt = await repository.getNextAttempt(values.document.id);
  const ocrJob = await repository.createJob({
    id: randomUUID(),
    documentId: values.document.id,
    jobType: "run_ocr",
    status: "queued",
    attempt: nextAttempt
  });

  await repository.markDocumentOcrQueued(values.document.id);
  await repository.createProcessingEvent({
    documentId: values.document.id,
    eventType: "ocr_queued",
    stage: "ocr",
    status: "pending",
    summary: "检测到扫描件正文为空，已转入 OCR 队列。",
    payload: {
      parse_job_id: values.jobId,
      ocr_job_id: ocrJob.id
    }
  });

  try {
    const queueJobId = await enqueueOcr(values.document.id, values.uploadId);
    await repository.updateJobQueueId(ocrJob.id, queueJobId);
    await repository.markJobCompleted(values.jobId);

    return {
      documentId: values.document.id,
      status: "success"
    };
  } catch (error) {
    const message = error instanceof Error ? error.message : "failed to enqueue ocr job";
    await repository.markDocumentOcrFailed(values.document.id, message, "queue_backlog");
    await repository.markJobFailed(ocrJob.id, message, "queue_backlog", context.attemptsMade >= 2);
    await repository.markJobFailed(values.jobId, message, "queue_backlog", context.attemptsMade >= 2);
    await repository.createProcessingEvent({
      documentId: values.document.id,
      eventType: "ocr_enqueue_failed",
      stage: "ocr",
      status: "failed",
      diagnosisCode: "queue_backlog",
      summary: "OCR 任务入队失败。",
      payload: {
        ocr_job_id: ocrJob.id
      }
    });

    return {
      documentId: values.document.id,
      status: "failed",
      reason: message
    };
  }
}

async function queueIndexingForDocument(
  documentId: string,
  deps: DocumentProcessingDependencies,
  values: {
    summary: string;
  }
): Promise<boolean> {
  const { repository, enqueueChunkDocument } = deps;
  const nextAttempt = await repository.getNextAttempt(documentId);
  const indexingJob = await repository.createJob({
    id: randomUUID(),
    documentId,
    jobType: "chunk_document",
    status: "queued",
    attempt: nextAttempt
  });

  await repository.markDocumentIndexQueued(documentId);
  await repository.createProcessingEvent({
    documentId,
    eventType: "index_queued",
    stage: "index",
    status: "pending",
    summary: values.summary,
    payload: {
      job_id: indexingJob.id
    }
  });

  try {
    const queueJobId = await enqueueChunkDocument(documentId, indexingJob.id);
    await repository.updateJobQueueId(indexingJob.id, queueJobId);
    return true;
  } catch (error) {
    const message = error instanceof Error ? error.message : "failed to enqueue indexing job";
    await repository.markDocumentIndexFailed(documentId, "索引任务未能入队，请稍后重试。", "queue_backlog");
    await repository.markJobFailed(indexingJob.id, message, "queue_backlog");
    await repository.createProcessingEvent({
      documentId,
      eventType: "index_enqueue_failed",
      stage: "index",
      status: "failed",
      diagnosisCode: "queue_backlog",
      summary: "问答索引任务入队失败。",
      payload: {
        job_id: indexingJob.id
      }
    });
    return false;
  }
}

type WorkerDocumentRecordLike = {
  id: string;
  title: string;
  source_url: string | null;
  file_name: string | null;
  mime_type: string | null;
  object_key: string | null;
};

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
    const indexingQueued = await queueIndexingForDocument(document.id, deps, {
      summary: "链接正文抓取成功，已排入问答索引队列。"
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
      summary: indexingQueued ? "链接正文抓取成功，已写入搜索投影并排入问答索引。" : "链接正文抓取成功，已写入搜索投影，但问答索引入队失败。",
      payload: {
        fetch_id: fetchRecord.id,
        canonical_url: parsed.canonicalUrl,
        parser_name: parsed.parserName,
        indexing_queued: indexingQueued
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

async function processOcr(context: JobContext, deps: DocumentProcessingDependencies): Promise<DocumentProcessingJobResult> {
  const { repository, storage, logger } = deps;
  const runOcr = deps.runOcr ?? runPdfOcr;
  const job = await repository.findJob(context.id, context.data.documentId, "run_ocr");

  if (!job) {
    logger.warn("ocr job record not found", {
      queueJobId: context.id,
      documentId: context.data.documentId,
      jobType: "run_ocr"
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

  if (!document.object_key) {
    await repository.markDocumentOcrFailed(document.id, "OCR 缺少源文件对象。", "ocr_runtime_error");
    await repository.markJobFailed(job.id, "document object key is empty", "ocr_runtime_error", context.attemptsMade >= 2);
    return {
      documentId: document.id,
      status: "failed",
      reason: "document object key is empty"
    };
  }

  await repository.markJobRunning(job.id, context.id);
  await repository.markDocumentOcrProcessing(document.id);
  await repository.createProcessingEvent({
    documentId: document.id,
    eventType: "ocr_started",
    stage: "ocr",
    status: "processing",
    summary: "OCR worker 开始处理扫描件 PDF。",
    payload: {
      job_id: job.id
    }
  });

  try {
    const bytes = await storage.getObjectBytes(document.object_key);
    const parsed = await runOcr(bytes);
    const tagNames = await repository.listDocumentTags(document.id);
    const contentClean = normalizeWhitespace(parsed.text);

    await repository.markDocumentOcrSuccess(document.id, {
      contentRaw: parsed.text,
      contentClean,
      contentPreview: createContentPreview(contentClean),
      searchText: buildSearchText({
        title: document.title,
        contentClean,
        tags: tagNames,
        fileName: document.file_name,
        sourceUrl: document.source_url
      }),
      pageCount: parsed.pageCount,
      ocrEngine: parsed.ocrEngine,
      ocrLanguage: parsed.ocrLanguage
    });
    const indexingQueued = await queueIndexingForDocument(document.id, deps, {
      summary: "OCR 成功，已排入问答索引队列。"
    });
    await repository.createProcessingEvent({
      documentId: document.id,
      eventType: "ocr_succeeded",
      stage: "ocr",
      status: "success",
      summary: indexingQueued ? "OCR 成功，已写入搜索投影并排入问答索引。" : "OCR 成功，已写入搜索投影，但问答索引入队失败。",
      payload: {
        ocr_engine: parsed.ocrEngine,
        ocr_language: parsed.ocrLanguage,
        page_count: parsed.pageCount,
        indexing_queued: indexingQueued
      }
    });
    await repository.markJobCompleted(job.id);

    return {
      documentId: document.id,
      status: "success"
    };
  } catch (error) {
    const message = error instanceof Error ? error.message : "ocr processing failed";
    const diagnosisCode = mapOcrDiagnosisCode(message);
    await repository.markDocumentOcrFailed(document.id, message, diagnosisCode, {
      ocrEngine: "tesseract-ocr"
    });
    await repository.markJobFailed(job.id, message, diagnosisCode, context.attemptsMade >= 2);
    await repository.createProcessingEvent({
      documentId: document.id,
      eventType: "ocr_failed",
      stage: "ocr",
      status: "failed",
      diagnosisCode,
      summary: "OCR 处理失败。",
      payload: null
    });

    return {
      documentId: document.id,
      status: "failed",
      reason: message
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
    [DOCUMENT_PROCESSING_JOB_NAMES.runOcr]: (context: JobContext) => processOcr(context, deps),
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
