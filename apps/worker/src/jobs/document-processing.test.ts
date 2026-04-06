import assert from "node:assert/strict";
import test from "node:test";
import {
  createDocumentProcessingHandlers,
  mapDiagnosisCode,
  type DocumentProcessingDependencies
} from "./document-processing";
import { DOCUMENT_PROCESSING_JOB_NAMES } from "../queue/constants";

test("mapDiagnosisCode returns timeout code for PDF timeout failures", () => {
  assert.equal(mapDiagnosisCode("PDF parser timeout exceeded", "application/pdf"), "pdf_parse_timeout");
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
    markJobCompleted: async () => {},
    markDocumentFailed: async () => {},
    markJobFailed: async () => {}
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
    ...overrides
  };
}

test("parse-document marks PDF document success and records parser metadata", async () => {
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

  const deps = createDeps({
    repository: {
      ...(createDeps().repository as object),
      markDocumentSuccess: async (_documentId, values) => {
        successPayload = values;
      },
      markJobCompleted: async (jobId) => {
        completedJobId = jobId;
      }
    } as DocumentProcessingDependencies["repository"],
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
      markDocumentFailed: async (_documentId, message, diagnosisCode) => {
        failedDocument = {
          message,
          diagnosisCode
        };
      },
      markJobFailed: async (_jobId, message, diagnosisCode, dead) => {
        failedJob = {
          message,
          diagnosisCode,
          dead: Boolean(dead)
        };
      }
    } as DocumentProcessingDependencies["repository"],
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
