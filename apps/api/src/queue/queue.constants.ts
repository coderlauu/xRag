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
