export type {
  AnswerOrchestrationJobData,
  AnswerOrchestrationJobName,
  DocumentIndexingJobData,
  DocumentIndexingJobName,
  DocumentProcessingJobName
} from "@xrag/shared-types";

import type {
  AnswerOrchestrationJobName,
  DocumentIndexingJobName,
  DocumentProcessingJobName
} from "@xrag/shared-types";

export const DOCUMENT_PROCESSING_QUEUE_NAME = "document-processing";
export const DOCUMENT_INDEXING_QUEUE_NAME = "document-indexing";
export const ANSWER_ORCHESTRATION_QUEUE_NAME = "answer-orchestration";

export const DOCUMENT_PROCESSING_JOB_NAMES = {
  parseDocument: "parse_document",
  reparseDocument: "reparse_document",
  refreshSearchProjection: "refresh_search_projection",
  runOcr: "run_ocr",
  fetchLink: "fetch_link",
  rebuildSearchProjection: "rebuild_search_projection"
} as const satisfies Record<string, DocumentProcessingJobName>;

export const DOCUMENT_INDEXING_JOB_NAMES = {
  chunkDocument: "chunk_document",
  embedDocument: "embed_document"
} as const satisfies Record<string, DocumentIndexingJobName>;

export const ANSWER_ORCHESTRATION_JOB_NAMES = {
  answerSession: "answer_session"
} as const satisfies Record<string, AnswerOrchestrationJobName>;
