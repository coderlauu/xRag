import { DOCUMENT_PROCESSING_JOB_NAMES, type DocumentProcessingJobName } from "../queue/constants";
import type { Logger } from "../logging/logger";

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

async function parseDocumentJob(context: JobContext, logger: Logger): Promise<DocumentProcessingJobResult> {
  logger.info("parse job received", {
    jobName: context.name,
    jobId: context.id,
    documentId: context.data.documentId,
    attemptsMade: context.attemptsMade
  });

  return {
    documentId: context.data.documentId,
    status: "skipped",
    reason: "parse implementation not wired yet"
  };
}

async function reparseDocumentJob(context: JobContext, logger: Logger): Promise<DocumentProcessingJobResult> {
  logger.info("reparse job received", {
    jobName: context.name,
    jobId: context.id,
    documentId: context.data.documentId,
    attemptsMade: context.attemptsMade
  });

  return {
    documentId: context.data.documentId,
    status: "skipped",
    reason: "reparse implementation not wired yet"
  };
}

async function refreshSearchProjectionJob(
  context: JobContext,
  logger: Logger
): Promise<DocumentProcessingJobResult> {
  logger.info("search projection refresh received", {
    jobName: context.name,
    jobId: context.id,
    documentId: context.data.documentId,
    attemptsMade: context.attemptsMade
  });

  return {
    documentId: context.data.documentId,
    status: "skipped",
    reason: "search projection implementation not wired yet"
  };
}

export const documentProcessingHandlers: Record<
  DocumentProcessingJobName,
  (context: JobContext, logger: Logger) => Promise<DocumentProcessingJobResult>
> = {
  [DOCUMENT_PROCESSING_JOB_NAMES.parseDocument]: parseDocumentJob,
  [DOCUMENT_PROCESSING_JOB_NAMES.reparseDocument]: reparseDocumentJob,
  [DOCUMENT_PROCESSING_JOB_NAMES.refreshSearchProjection]: refreshSearchProjectionJob
};
