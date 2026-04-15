import assert from "node:assert/strict";
import test from "node:test";
import {
  createDocumentProcessingHandlers,
  mapDiagnosisCode,
  mapOcrDiagnosisCode,
  type DocumentProcessingDependencies
} from "./document-processing";
import { DOCUMENT_PROCESSING_JOB_NAMES } from "../queue/constants";
import { LinkFetchError } from "./link-parser";

test("mapDiagnosisCode returns timeout code for PDF timeout failures", () => {
  assert.equal(mapDiagnosisCode("PDF parser timeout exceeded", "application/pdf"), "pdf_parse_timeout");
});

test("mapDiagnosisCode returns runtime-error code for PDF clone/runtime failures", () => {
  assert.equal(
    mapDiagnosisCode("Cannot transfer object of unsupported type.", "application/pdf"),
    "pdf_parse_runtime_error"
  );
});

test("mapDiagnosisCode returns empty-text code for PDF empty text failures", () => {
  assert.equal(mapDiagnosisCode("pdf extraction returned empty text", "application/pdf"), "pdf_parse_empty_text");
});

test("mapDiagnosisCode returns unsupported code for non-timeout PDF failures", () => {
  assert.equal(mapDiagnosisCode("pdf renderer crashed", "application/pdf"), "pdf_parse_unsupported");
});

test("mapDiagnosisCode returns null for non-PDF failures", () => {
  assert.equal(mapDiagnosisCode("network reset by peer", "text/plain"), null);
});

test("mapOcrDiagnosisCode returns timeout code for OCR timeout failures", () => {
  assert.equal(mapOcrDiagnosisCode("ocr pipeline timeout exceeded"), "ocr_timeout");
});

test("mapOcrDiagnosisCode returns no-text code for OCR empty results", () => {
  assert.equal(mapOcrDiagnosisCode("ocr pipeline returned empty text"), "ocr_no_text_detected");
});

test("mapOcrDiagnosisCode returns runtime code for other OCR failures", () => {
  assert.equal(mapOcrDiagnosisCode("tesseract exited with status 1"), "ocr_runtime_error");
});

function createDeps(overrides: Partial<DocumentProcessingDependencies> = {}): DocumentProcessingDependencies {
  const repository = {
    findJob: async () => ({
      id: "job-1",
      document_id: "doc-1",
      job_type: "parse_document",
      status: "queued",
      queue_job_id: null,
      attempt: 0,
      started_at: null
    }),
    findDocument: async () => ({
      id: "doc-1",
      title: "PDF 文档",
      content_raw: null,
      content_clean: null,
      source_url: null,
      file_name: "doc.pdf",
      mime_type: "application/pdf",
      object_key: "uploads/doc.pdf"
    }),
    listDocumentTags: async () => ["P1", "测试"],
    markJobRunning: async () => {},
    markDocumentProcessing: async () => {},
    markDocumentSuccess: async () => {},
    markDocumentIndexQueued: async () => {},
    markDocumentIndexFailed: async () => {},
    markJobCompleted: async () => {},
    markDocumentFailed: async () => {},
    markJobFailed: async () => {},
    getNextAttempt: async () => 2,
    createJob: async () => ({
      id: "job-index-1",
      document_id: "doc-1",
      job_type: "chunk_document",
      status: "queued",
      queue_job_id: null,
      attempt: 2,
      started_at: null
    }),
    updateJobQueueId: async () => {},
    createProcessingEvent: async () => {}
  } as unknown as DocumentProcessingDependencies["repository"];

  const storage = {
    getObjectBody: async () => "",
    getObjectBytes: async () => new Uint8Array([1, 2, 3])
  } as unknown as DocumentProcessingDependencies["storage"];

  const logger = {
    warn: () => {},
    info: () => {},
    error: () => {}
  } as unknown as DocumentProcessingDependencies["logger"];

  return {
    repository,
    storage,
    logger,
    enqueueChunkDocument: async () => "queue-index-1",
    ...overrides
  };
}

test("parse-document marks PDF document success, queues indexing and records parser metadata", async () => {
  let successPayload:
    | {
        contentRaw: string | null;
        contentClean: string;
        contentPreview: string;
        searchText: string;
        pageCount?: number | null;
        parserName?: string | null;
        parserVersion?: string | null;
      }
    | undefined;
  let completedJobId: string | undefined;
  let indexingQueued = false;
  let indexQueueJobId: string | undefined;

  const deps = createDeps({
    repository: {
      ...(createDeps().repository as object),
      markDocumentSuccess: async (
        _documentId: string,
        values: {
          title?: string | null;
          contentRaw: string | null;
          contentClean: string;
          contentPreview: string;
          searchText: string;
          mimeType?: string | null;
          pageCount?: number | null;
          parserName?: string | null;
          parserVersion?: string | null;
        }
      ) => {
        successPayload = values;
      },
      markJobCompleted: async (jobId: string) => {
        completedJobId = jobId;
      },
      markDocumentIndexQueued: async () => {
        indexingQueued = true;
      },
      updateJobQueueId: async (_jobId: string, queueJobId: string) => {
        indexQueueJobId = queueJobId;
      }
    } as unknown as DocumentProcessingDependencies["repository"],
    parsePdf: async () => ({
      text: "第一页\n第二页",
      pageCount: 2,
      parserName: "pdf-parse",
      parserVersion: "2.4.5"
    })
  });

  const handlers = createDocumentProcessingHandlers(deps);
  const result = await handlers[DOCUMENT_PROCESSING_JOB_NAMES.parseDocument]({
    name: DOCUMENT_PROCESSING_JOB_NAMES.parseDocument,
    id: "queue-job-1",
    data: {
      documentId: "doc-1"
    },
    attemptsMade: 0
  });

  assert.equal(result.status, "success");
  assert.equal(completedJobId, "job-1");
  assert.equal(indexingQueued, true);
  assert.equal(indexQueueJobId, "queue-index-1");
  assert.equal(successPayload?.pageCount, 2);
  assert.equal(successPayload?.parserName, "pdf-parse");
  assert.equal(successPayload?.parserVersion, "2.4.5");
  assert.match(successPayload?.contentPreview || "", /第一页/);
});

test("parse-document maps PDF timeout failures into diagnosis codes", async () => {
  let failedDocument:
    | {
        message: string;
        diagnosisCode: string | null;
      }
    | undefined;
  let failedJob:
    | {
        message: string;
        diagnosisCode: string | null;
        dead: boolean;
      }
    | undefined;

  const deps = createDeps({
    repository: {
      ...(createDeps().repository as object),
      markDocumentFailed: async (_documentId: string, message: string, diagnosisCode: string | null) => {
        failedDocument = {
          message,
          diagnosisCode
        };
      },
      markJobFailed: async (
        _jobId: string,
        message: string,
        diagnosisCode: string | null,
        dead?: boolean
      ) => {
        failedJob = {
          message,
          diagnosisCode,
          dead: Boolean(dead)
        };
      }
    } as unknown as DocumentProcessingDependencies["repository"],
    parsePdf: async () => {
      throw new Error("PDF parser timeout exceeded");
    }
  });

  const handlers = createDocumentProcessingHandlers(deps);
  const result = await handlers[DOCUMENT_PROCESSING_JOB_NAMES.parseDocument]({
    name: DOCUMENT_PROCESSING_JOB_NAMES.parseDocument,
    id: "queue-job-1",
    data: {
      documentId: "doc-1"
    },
    attemptsMade: 2
  });

  assert.equal(result.status, "failed");
  assert.equal(failedDocument?.diagnosisCode, "pdf_parse_timeout");
  assert.equal(failedJob?.diagnosisCode, "pdf_parse_timeout");
  assert.equal(failedJob?.dead, true);
});

test("parse-document queues OCR when PDF text extraction is empty", async () => {
  let ocrQueued = false;
  let parseCompleted = false;
  let ocrQueuedState = false;

  const deps = createDeps({
    repository: {
      ...(createDeps().repository as object),
      getNextAttempt: async () => 2,
      createJob: async () => ({
        id: "job-ocr-1",
        document_id: "doc-1",
        job_type: "run_ocr",
        status: "queued",
        queue_job_id: null,
        attempt: 2,
        started_at: null
      }),
      updateJobQueueId: async () => {},
      markDocumentOcrQueued: async () => {
        ocrQueuedState = true;
      },
      createProcessingEvent: async () => {},
      markJobCompleted: async () => {
        parseCompleted = true;
      }
    } as unknown as DocumentProcessingDependencies["repository"],
    parsePdf: async () => {
      throw new Error("pdf extraction returned empty text");
    },
    enqueueOcr: async () => {
      ocrQueued = true;
      return "queue-ocr-1";
    }
  });

  const handlers = createDocumentProcessingHandlers(deps);
  const result = await handlers[DOCUMENT_PROCESSING_JOB_NAMES.parseDocument]({
    name: DOCUMENT_PROCESSING_JOB_NAMES.parseDocument,
    id: "queue-job-parse-1",
    data: {
      documentId: "doc-1",
      uploadId: "upload-1"
    },
    attemptsMade: 0
  });

  assert.equal(result.status, "success");
  assert.equal(ocrQueued, true);
  assert.equal(ocrQueuedState, true);
  assert.equal(parseCompleted, true);
});

test("run-ocr stores OCR text and completes the job", async () => {
  let ocrSuccessPayload:
    | {
        contentRaw: string | null;
        contentClean: string;
        contentPreview: string;
        searchText: string;
        pageCount?: number | null;
        ocrEngine: string;
        ocrLanguage: string;
      }
    | undefined;
  let completedJobId: string | undefined;
  let indexingQueued = false;

  const deps = createDeps({
    repository: {
      ...(createDeps().repository as object),
      findJob: async () => ({
        id: "job-ocr-1",
        document_id: "doc-1",
        job_type: "run_ocr",
        status: "queued",
        queue_job_id: null,
        attempt: 1,
        started_at: null
      }),
      markDocumentOcrProcessing: async () => {},
      markDocumentOcrSuccess: async (_documentId: string, values: typeof ocrSuccessPayload extends infer _T ? {
        contentRaw: string | null;
        contentClean: string;
        contentPreview: string;
        searchText: string;
        pageCount?: number | null;
        ocrEngine: string;
        ocrLanguage: string;
      } : never) => {
        ocrSuccessPayload = values;
      },
      createProcessingEvent: async () => {},
      markDocumentIndexQueued: async () => {
        indexingQueued = true;
      },
      markJobCompleted: async (jobId: string) => {
        completedJobId = jobId;
      }
    } as unknown as DocumentProcessingDependencies["repository"],
    runOcr: async () => ({
      text: "这是 OCR 识别后的正文。",
      pageCount: 2,
      ocrEngine: "tesseract-ocr",
      ocrLanguage: "chi_sim+eng"
    })
  });

  const handlers = createDocumentProcessingHandlers(deps);
  const result = await handlers[DOCUMENT_PROCESSING_JOB_NAMES.runOcr]({
    name: DOCUMENT_PROCESSING_JOB_NAMES.runOcr,
    id: "queue-job-ocr-1",
    data: {
      documentId: "doc-1"
    },
    attemptsMade: 0
  });

  assert.equal(result.status, "success");
  assert.equal(completedJobId, "job-ocr-1");
  assert.equal(indexingQueued, true);
  assert.equal(ocrSuccessPayload?.ocrEngine, "tesseract-ocr");
  assert.equal(ocrSuccessPayload?.ocrLanguage, "chi_sim+eng");
  assert.match(ocrSuccessPayload?.contentPreview || "", /OCR 识别后的正文/);
});

test("fetch-link stores extracted text and completes the job", async () => {
  let successPayload:
    | {
        title?: string | null;
        contentRaw: string | null;
        contentClean: string;
        contentPreview: string;
        searchText: string;
        mimeType?: string | null;
        parserName?: string | null;
        parserVersion?: string | null;
      }
    | undefined;
  let sourceFetchSucceeded = false;
  let completedJobId: string | undefined;
  let indexingQueued = false;

  const deps = createDeps({
    repository: {
      ...(createDeps().repository as object),
      findJob: async () => ({
        id: "job-link-1",
        document_id: "doc-1",
        job_type: "fetch_link",
        status: "queued",
        queue_job_id: null,
        attempt: 0,
        started_at: null
      }),
      findDocument: async () => ({
        id: "doc-1",
        title: "https://example.com/roadmap",
        content_raw: null,
        content_clean: null,
        source_url: "https://example.com/roadmap",
        file_name: null,
        mime_type: null,
        object_key: null
      }),
      createSourceFetch: async () => ({
        id: "fetch-1",
        document_id: "doc-1",
        source_url: "https://example.com/roadmap",
        fetch_status: "queued"
      }),
      markSourceFetchRunning: async () => {},
      markSourceFetchSucceeded: async () => {
        sourceFetchSucceeded = true;
      },
      createProcessingEvent: async () => {},
      markDocumentIndexQueued: async () => {
        indexingQueued = true;
      },
      markDocumentSuccess: async (
        _documentId: string,
        values: {
          title?: string | null;
          contentRaw: string | null;
          contentClean: string;
          contentPreview: string;
          searchText: string;
          mimeType?: string | null;
          pageCount?: number | null;
          parserName?: string | null;
          parserVersion?: string | null;
        }
      ) => {
        successPayload = values;
      },
      markJobCompleted: async (jobId: string) => {
        completedJobId = jobId;
      }
    } as unknown as DocumentProcessingDependencies["repository"],
    fetchLink: async () => ({
      sourceUrl: "https://example.com/roadmap",
      canonicalUrl: "https://example.com/roadmap",
      contentType: "text/html",
      title: "路线图更新",
      text: "这是抓取到的正文。",
      parserName: "link-fetcher",
      parserVersion: "1.0.0"
    })
  });

  const handlers = createDocumentProcessingHandlers(deps);
  const result = await handlers[DOCUMENT_PROCESSING_JOB_NAMES.fetchLink]({
    name: DOCUMENT_PROCESSING_JOB_NAMES.fetchLink,
    id: "queue-job-link-1",
    data: {
      documentId: "doc-1"
    },
    attemptsMade: 0
  });

  assert.equal(result.status, "success");
  assert.equal(completedJobId, "job-link-1");
  assert.equal(sourceFetchSucceeded, true);
  assert.equal(indexingQueued, true);
  assert.equal(successPayload?.title, "路线图更新");
  assert.equal(successPayload?.parserName, "link-fetcher");
  assert.match(successPayload?.contentPreview || "", /抓取到的正文/);
});

test("parse-document marks indexing failed when chunk job enqueue fails", async () => {
  let indexFailure:
    | {
        message: string;
        diagnosisCode: string | null;
      }
    | undefined;
  let failedJob:
    | {
        message: string;
        diagnosisCode: string | null;
      }
    | undefined;

  const deps = createDeps({
    repository: {
      ...(createDeps().repository as object),
      markDocumentIndexFailed: async (_documentId: string, message: string, diagnosisCode: string | null) => {
        indexFailure = {
          message,
          diagnosisCode
        };
      },
      markJobFailed: async (_jobId: string, message: string, diagnosisCode: string | null) => {
        failedJob = {
          message,
          diagnosisCode
        };
      }
    } as unknown as DocumentProcessingDependencies["repository"],
    parsePdf: async () => ({
      text: "第一页\n第二页",
      pageCount: 2,
      parserName: "pdf-parse",
      parserVersion: "2.4.5"
    }),
    enqueueChunkDocument: async () => {
      throw new Error("redis unavailable");
    }
  });

  const handlers = createDocumentProcessingHandlers(deps);
  const result = await handlers[DOCUMENT_PROCESSING_JOB_NAMES.parseDocument]({
    name: DOCUMENT_PROCESSING_JOB_NAMES.parseDocument,
    id: "queue-job-1",
    data: {
      documentId: "doc-1"
    },
    attemptsMade: 0
  });

  assert.equal(result.status, "success");
  assert.equal(indexFailure?.diagnosisCode, "queue_backlog");
  assert.match(indexFailure?.message || "", /索引任务未能入队/);
  assert.equal(failedJob?.diagnosisCode, "queue_backlog");
  assert.equal(failedJob?.message, "redis unavailable");
});

test("fetch-link maps blocked errors into diagnosis codes", async () => {
  let failedDocument:
    | {
        message: string;
        diagnosisCode: string | null;
      }
    | undefined;

  const deps = createDeps({
    repository: {
      ...(createDeps().repository as object),
      findJob: async () => ({
        id: "job-link-1",
        document_id: "doc-1",
        job_type: "fetch_link",
        status: "queued",
        queue_job_id: null,
        attempt: 0,
        started_at: null
      }),
      findDocument: async () => ({
        id: "doc-1",
        title: "https://example.com/roadmap",
        content_raw: null,
        content_clean: null,
        source_url: "https://example.com/roadmap",
        file_name: null,
        mime_type: null,
        object_key: null
      }),
      createSourceFetch: async () => ({
        id: "fetch-1",
        document_id: "doc-1",
        source_url: "https://example.com/roadmap",
        fetch_status: "queued"
      }),
      markSourceFetchRunning: async () => {},
      markSourceFetchFailed: async () => {},
      createProcessingEvent: async () => {},
      markDocumentFailed: async (_documentId: string, message: string, diagnosisCode: string | null) => {
        failedDocument = {
          message,
          diagnosisCode
        };
      }
    } as unknown as DocumentProcessingDependencies["repository"],
    fetchLink: async () => {
      throw new LinkFetchError("Link fetch failed with status 403", "link_fetch_blocked", 403, "text/html");
    }
  });

  const handlers = createDocumentProcessingHandlers(deps);
  const result = await handlers[DOCUMENT_PROCESSING_JOB_NAMES.fetchLink]({
    name: DOCUMENT_PROCESSING_JOB_NAMES.fetchLink,
    id: "queue-job-link-1",
    data: {
      documentId: "doc-1"
    },
    attemptsMade: 0
  });

  assert.equal(result.status, "failed");
  assert.equal(failedDocument?.diagnosisCode, "link_fetch_blocked");
});
