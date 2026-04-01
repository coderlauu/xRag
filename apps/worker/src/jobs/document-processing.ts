import { normalizeWhitespace, createContentPreview, buildSearchText, inferTextSupport } from "../common/document-utils";
import type { WorkerRepository } from "../database/repository";
import type { Logger } from "../logging/logger";
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
}

async function processDocument(context: JobContext, deps: DocumentProcessingDependencies): Promise<DocumentProcessingJobResult> {
  const { repository, storage, logger } = deps;
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
    await repository.markJobFailed(job.id, "document not found", context.attemptsMade >= 2);
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
    await repository.markDocumentFailed(document.id, reason);
    await repository.markJobFailed(job.id, reason, dead);
    return {
      documentId: document.id,
      status: "failed",
      reason
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
    [DOCUMENT_PROCESSING_JOB_NAMES.refreshSearchProjection]: async (context: JobContext) => ({
      documentId: context.data.documentId,
      status: "skipped" as const,
      reason: "search projection refresh not wired yet"
    })
  } satisfies Record<DocumentProcessingJobName, (context: JobContext) => Promise<DocumentProcessingJobResult>>;
}
