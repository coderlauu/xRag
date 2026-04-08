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
} as const;

export type DocumentProcessingJobName =
  (typeof DOCUMENT_PROCESSING_JOB_NAMES)[keyof typeof DOCUMENT_PROCESSING_JOB_NAMES];

export const DOCUMENT_INDEXING_JOB_NAMES = {
  chunkDocument: "chunk_document",
  embedDocument: "embed_document"
} as const;

export type DocumentIndexingJobName =
  (typeof DOCUMENT_INDEXING_JOB_NAMES)[keyof typeof DOCUMENT_INDEXING_JOB_NAMES];

export const ANSWER_ORCHESTRATION_JOB_NAMES = {
  answerSession: "answer_session"
} as const;

export type AnswerOrchestrationJobName =
  (typeof ANSWER_ORCHESTRATION_JOB_NAMES)[keyof typeof ANSWER_ORCHESTRATION_JOB_NAMES];

export interface DocumentIndexingJobData {
  documentId: string;
  jobId: string;
}

export interface AnswerOrchestrationJobData {
  sessionId: string;
}
