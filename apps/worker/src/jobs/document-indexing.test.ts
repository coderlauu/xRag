import assert from "node:assert/strict";
import test from "node:test";
import { DOCUMENT_INDEXING_JOB_NAMES } from "../queue/constants";
import { buildIndexVersion, chunkDocumentText } from "../indexing";
import { createDocumentIndexingHandlers, type DocumentIndexingDependencies } from "./document-indexing";

test("buildIndexVersion encodes model and dimension", () => {
  assert.equal(buildIndexVersion("text-embedding-3-small"), "phase-2a-index-v1:text-embedding-3-small:1536");
});

test("chunkDocumentText splits long content into deterministic chunks", () => {
  const chunks = chunkDocumentText({
    documentId: "doc-1",
    title: "Example",
    text: "# Section One\n\n" + "alpha ".repeat(500) + "\n\n# Section Two\n\nbeta gamma delta",
    strategyVersion: "phase-2a-index-v1:text-embedding-3-small:1536",
    maxChunkChars: 120,
    splitOverlapChars: 20
  });

  assert.equal(chunks.length > 1, true);
  assert.equal(chunks[0]?.sectionLabel, "Section One");
  assert.equal(chunks.some((chunk) => /alpha/.test(chunk.contentText)), true);
  assert.equal(chunks[0]?.citationLocator.chunk_index, 0);
});

function createDeps(overrides: Partial<DocumentIndexingDependencies> = {}): DocumentIndexingDependencies {
  const repository = {
    findJob: async () => ({
      id: "job-1",
      document_id: "doc-1",
      job_type: DOCUMENT_INDEXING_JOB_NAMES.chunkDocument,
      status: "queued",
      queue_job_id: null,
      attempt: 0,
      started_at: null
    }),
    findDocument: async () => ({
      id: "doc-1",
      title: "Example",
      contentRaw: "line one\n\nline two",
      contentClean: "line one line two",
      sourceUrl: null,
      fileName: null,
      mimeType: "text/plain",
      objectKey: null,
      parseStatus: "success",
      indexStatus: "queued",
      indexVersion: null,
      indexedAt: null,
      citationReady: false,
      diagnosisCode: null,
      diagnosisSummary: null,
      pageCount: null,
      parserName: null,
      parserVersion: null
    }),
    getNextAttempt: async () => 2,
    createJob: async () => ({
      id: "job-embed-1",
      document_id: "doc-1",
      job_type: DOCUMENT_INDEXING_JOB_NAMES.embedDocument,
      status: "queued",
      queue_job_id: null,
      attempt: 2,
      started_at: null
    }),
    updateJobQueueId: async () => {},
    markJobRunning: async () => {},
    markJobCompleted: async () => {},
    markJobFailed: async () => {},
    markDocumentIndexChunking: async () => {},
    markDocumentIndexEmbedding: async () => {},
    markDocumentIndexReady: async () => {},
    markDocumentIndexFailed: async () => {},
    deleteDocumentChunks: async () => {},
    insertDocumentChunks: async () => [],
    listDocumentChunks: async () => [],
    updateChunkEmbedding: async () => {},
    createProcessingEvent: async () => {},
    withTransaction: async (callback: (db: { query: () => Promise<unknown> }) => Promise<unknown>) =>
      callback({ query: async () => ({ rowCount: 1, rows: [] }) })
  } as unknown as DocumentIndexingDependencies["repository"];

  return {
    repository,
    logger: {
      debug: () => {},
      info: () => {},
      warn: () => {},
      error: () => {}
    },
    embeddingModel: "text-embedding-3-small",
    getEmbeddingProvider: () => ({
      embed: async (input: string | readonly string[]) => {
        const values = Array.isArray(input) ? input : [input];
        return {
          providerName: "openai-compatible",
          model: "text-embedding-3-small",
          vectors: values.map((_, index) => [index + 1, index + 2, index + 3]),
          dimensions: 3,
          inputCount: values.length,
          usage: null,
          attempts: 1,
          latencyMs: 1,
          raw: null
        };
      }
    }),
    enqueueEmbedDocument: async () => "queue-job-embed-1",
    ...overrides
  };
}

test("chunk job queues embed job and moves document into embedding state", async () => {
  let queueJobId: string | undefined;
  let documentEmbeddingMarked = false;
  let jobCompleted = false;
  const events: string[] = [];

  const deps = createDeps({
    repository: {
      ...(createDeps().repository as object),
      updateJobQueueId: async (_jobId: string, queueJob: string) => {
        queueJobId = queueJob;
      },
      markDocumentIndexEmbedding: async () => {
        documentEmbeddingMarked = true;
      },
      markJobCompleted: async () => {
        jobCompleted = true;
      },
      createProcessingEvent: async (values: { eventType: string }) => {
        events.push(values.eventType);
      },
      insertDocumentChunks: async () => [{ id: "chunk-1" } as never]
    } as unknown as DocumentIndexingDependencies["repository"]
  });

  const handlers = createDocumentIndexingHandlers(deps);
  const result = await handlers[DOCUMENT_INDEXING_JOB_NAMES.chunkDocument]({
    name: DOCUMENT_INDEXING_JOB_NAMES.chunkDocument,
    id: "queue-job-1",
    data: {
      documentId: "doc-1",
      jobId: "job-1"
    },
    attemptsMade: 0
  });

  assert.equal(result.status, "success");
  assert.equal(queueJobId, "queue-job-embed-1");
  assert.equal(documentEmbeddingMarked, true);
  assert.equal(jobCompleted, true);
  assert.deepEqual(events, ["chunk_started", "chunk_succeeded"]);
});

test("chunk job fails fast when parse status is not success", async () => {
  let diagnosisCode: string | null = null;
  let eventType: string | undefined;

  const deps = createDeps({
    repository: {
      ...(createDeps().repository as object),
      findDocument: async () => ({
        id: "doc-1",
        title: "Example",
        contentRaw: null,
        contentClean: null,
        sourceUrl: null,
        fileName: null,
        mimeType: "text/plain",
        objectKey: null,
        parseStatus: "pending",
        indexStatus: "queued",
        indexVersion: null,
        indexedAt: null,
        citationReady: false,
        diagnosisCode: null,
        diagnosisSummary: null,
        pageCount: null,
        parserName: null,
        parserVersion: null
      }),
      markDocumentIndexFailed: async (_documentId: string, _message: string, code: string | null) => {
        diagnosisCode = code;
      },
      createProcessingEvent: async (values: { eventType: string }) => {
        eventType = values.eventType;
      }
    } as unknown as DocumentIndexingDependencies["repository"]
  });

  const handlers = createDocumentIndexingHandlers(deps);
  const result = await handlers[DOCUMENT_INDEXING_JOB_NAMES.chunkDocument]({
    name: DOCUMENT_INDEXING_JOB_NAMES.chunkDocument,
    id: "queue-job-1",
    data: {
      documentId: "doc-1",
      jobId: "job-1"
    },
    attemptsMade: 0
  });

  assert.equal(result.status, "failed");
  assert.equal(diagnosisCode, "index_chunk_failed");
  assert.equal(eventType, "index_chunk_failed");
});

test("chunk job marks queue backlog when embedding enqueue fails", async () => {
  let diagnosisCode: string | null = null;
  let eventType: string | undefined;

  const deps = createDeps({
    enqueueEmbedDocument: async () => {
      throw new Error("redis unavailable");
    },
    repository: {
      ...(createDeps().repository as object),
      markDocumentIndexFailed: async (_documentId: string, _message: string, code: string | null) => {
        diagnosisCode = code;
      },
      createProcessingEvent: async (values: { eventType: string }) => {
        eventType = values.eventType;
      }
    } as unknown as DocumentIndexingDependencies["repository"]
  });

  const handlers = createDocumentIndexingHandlers(deps);
  const result = await handlers[DOCUMENT_INDEXING_JOB_NAMES.chunkDocument]({
    name: DOCUMENT_INDEXING_JOB_NAMES.chunkDocument,
    id: "queue-job-1",
    data: {
      documentId: "doc-1",
      jobId: "job-1"
    },
    attemptsMade: 0
  });

  assert.equal(result.status, "failed");
  assert.equal(diagnosisCode, "queue_backlog");
  assert.equal(eventType, "index_chunk_failed");
});

test("embed job writes embeddings and marks document ready", async () => {
  let readyIndexVersion: string | undefined;
  let completedJob = false;
  const updatedChunkIds: string[] = [];

  const deps = createDeps({
    repository: {
      ...(createDeps().repository as object),
      findJob: async () => ({
        id: "job-embed-1",
        document_id: "doc-1",
        job_type: DOCUMENT_INDEXING_JOB_NAMES.embedDocument,
        status: "queued",
        queue_job_id: null,
        attempt: 0,
        started_at: null
      }),
      listDocumentChunks: async () => [
        {
          id: "chunk-1",
          documentId: "doc-1",
          chunkIndex: 0,
          strategyVersion: "phase-2a-index-v1:text-embedding-3-small:1536",
          sectionLabel: null,
          pageRef: null,
          contentText: "line one",
          tokenCount: 2,
          contentSha256: "abc",
          embedding: null,
          citationLocator: null,
          createdAt: new Date()
        }
      ],
      updateChunkEmbedding: async (chunkId: string) => {
        updatedChunkIds.push(chunkId);
      },
      markDocumentIndexReady: async (_documentId: string, indexVersion: string) => {
        readyIndexVersion = indexVersion;
      },
      markJobCompleted: async () => {
        completedJob = true;
      }
    } as unknown as DocumentIndexingDependencies["repository"]
  });

  const handlers = createDocumentIndexingHandlers(deps);
  const result = await handlers[DOCUMENT_INDEXING_JOB_NAMES.embedDocument]({
    name: DOCUMENT_INDEXING_JOB_NAMES.embedDocument,
    id: "queue-job-2",
    data: {
      documentId: "doc-1",
      jobId: "job-embed-1"
    },
    attemptsMade: 0
  });

  assert.equal(result.status, "success");
  assert.equal(completedJob, true);
  assert.deepEqual(updatedChunkIds, ["chunk-1"]);
  assert.equal(readyIndexVersion, "phase-2a-index-v1:text-embedding-3-small:1536");
});
