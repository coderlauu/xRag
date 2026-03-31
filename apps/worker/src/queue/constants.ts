export const DOCUMENT_PROCESSING_QUEUE_NAME = "document-processing" as const;

export const DOCUMENT_PROCESSING_JOB_NAMES = {
  parseDocument: "parse-document",
  reparseDocument: "reparse-document",
  refreshSearchProjection: "refresh-search-projection"
} as const;

export type DocumentProcessingJobName =
  (typeof DOCUMENT_PROCESSING_JOB_NAMES)[keyof typeof DOCUMENT_PROCESSING_JOB_NAMES];
