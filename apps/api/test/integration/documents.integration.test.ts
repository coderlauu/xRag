import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { Pool } from "pg";
import { createApp } from "../../src/bootstrap";
import { QueueService } from "../../src/queue/queue.service";

const databaseUrl = process.env.DATABASE_URL || "postgresql://xrag:xrag@127.0.0.1:5432/xrag";

async function resetDatabase() {
  const pool = new Pool({
    connectionString: databaseUrl
  });

  try {
    await pool.query(
      "truncate table document_chunks, document_processing_events, document_parse_jobs, document_tags, uploads, tags, documents restart identity cascade"
    );
  } finally {
    await pool.end();
  }
}

async function updateDocumentFailureState(documentId: string) {
  const pool = new Pool({
    connectionString: databaseUrl
  });

  try {
    await pool.query(
      `
        update documents
        set parse_status = 'failed',
            parse_error_message = 'pdf parser timeout',
            diagnosis_code = 'pdf_parse_timeout',
            diagnosis_summary = '解析超时'
        where id = $1
      `,
      [documentId]
    );
  } finally {
    await pool.end();
  }
}

async function setDocumentParseSuccess(documentId: string) {
  const pool = new Pool({
    connectionString: databaseUrl
  });

  try {
    await pool.query(
      `
        update documents
        set parse_status = 'success',
            parse_error_message = null,
            diagnosis_code = null,
            diagnosis_summary = null,
            updated_at = now()
        where id = $1
      `,
      [documentId]
    );
  } finally {
    await pool.end();
  }
}

async function setDocumentIndexState(
  documentId: string,
  values: {
    index_status: string;
    citation_ready?: boolean;
    index_version?: string | null;
    indexed_at?: Date | null;
  }
) {
  const pool = new Pool({
    connectionString: databaseUrl
  });

  try {
    await pool.query(
      `
        update documents
        set index_status = $2,
            citation_ready = coalesce($3, citation_ready),
            index_version = $4,
            indexed_at = $5,
            updated_at = now()
        where id = $1
      `,
      [documentId, values.index_status, values.citation_ready ?? null, values.index_version ?? null, values.indexed_at ?? null]
    );
  } finally {
    await pool.end();
  }
}

async function insertDocumentChunk(documentId: string) {
  const pool = new Pool({
    connectionString: databaseUrl
  });
  const chunkId = randomUUID();

  try {
    await pool.query(
      `
        insert into document_chunks (
          id,
          document_id,
          chunk_index,
          strategy_version,
          section_label,
          page_ref,
          content_text,
          token_count,
          content_sha256,
          citation_locator,
          created_at
        )
        values ($1, $2, 0, 'phase-2a-test', '章节 1', 'p. 7', '可用于引用的证据片段。', 12, $3, $4, now())
      `,
      [chunkId, documentId, "b".repeat(64), { page: 7, offset: 12 }]
    );
  } finally {
    await pool.end();
  }

  return chunkId;
}

test("documents API persists a created text document and returns it from list/detail", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const createResponse = await app.inject({
      method: "POST",
      url: "/api/v1/documents/text",
      payload: {
        title: "Phase 1A integration document",
        content: "Knowledge inbox integration path",
        tags: ["integration", "phase-1a"]
      }
    });

    assert.equal(createResponse.statusCode, 201);
    const created = createResponse.json();
    assert.equal(created.parse_status, "success");
    assert.ok(created.id);

    const listResponse = await app.inject({
      method: "GET",
      url: "/api/v1/documents?q=integration"
    });
    assert.equal(listResponse.statusCode, 200);
    const list = listResponse.json();
    assert.equal(list.total, 1);
    assert.equal(list.items[0].title, "Phase 1A integration document");

    const detailResponse = await app.inject({
      method: "GET",
      url: `/api/v1/documents/${created.id}`
    });
    assert.equal(detailResponse.statusCode, 200);
    const detail = detailResponse.json();
    assert.equal(detail.id, created.id);
    assert.deepEqual(detail.tags, ["integration", "phase-1a"]);
    assert.equal(detail.parse_status, "success");
    assert.equal(detail.upload_status, null);
    assert.equal(detail.diagnosis_code, null);
    assert.equal(detail.latest_job_status, null);
  } finally {
    await app.close();
  }
});

test("documents API retry clears diagnosis and enqueues a reparse job", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const createResponse = await app.inject({
      method: "POST",
      url: "/api/v1/documents/text",
      payload: {
        title: "Retryable PDF document",
        content: "needs reparse",
        tags: ["retry", "integration"]
      }
    });

    assert.equal(createResponse.statusCode, 201);
    const created = createResponse.json();
    await updateDocumentFailureState(created.id);

    const retryResponse = await app.inject({
      method: "POST",
      url: `/api/v1/documents/${created.id}/retry`
    });

    assert.equal(retryResponse.statusCode, 201);
    const retried = retryResponse.json();
    assert.equal(retried.document_id, created.id);
    assert.equal(retried.parse_status, "pending");
    assert.equal(retried.diagnosis_code, null);
    assert.ok(retried.job_id);

    const detailResponse = await app.inject({
      method: "GET",
      url: `/api/v1/documents/${created.id}`
    });
    assert.equal(detailResponse.statusCode, 200);
    const detail = detailResponse.json();
    assert.equal(detail.parse_status, "pending");
    assert.equal(detail.diagnosis_code, null);
    assert.equal(detail.latest_job_status, "queued");
    assert.equal(detail.latest_job.status, "queued");
    assert.equal(detail.latest_job.diagnosis_code, null);
  } finally {
    await app.close();
  }
});

test("documents API exposes evidence chunks and reindex flow", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const queueService = app.get(QueueService) as { enqueueChunkDocument: (documentId: string, jobId: string) => Promise<string> };
    queueService.enqueueChunkDocument = async () => "queue-job-reindex-1";

    const createResponse = await app.inject({
      method: "POST",
      url: "/api/v1/documents/text",
      payload: {
        title: "Evidence document",
        content: "Evidence-backed content",
        tags: ["evidence", "integration"]
      }
    });

    assert.equal(createResponse.statusCode, 201);
    const created = createResponse.json();

    await setDocumentIndexState(created.id, {
      index_status: "ready",
      citation_ready: true,
      index_version: "phase-2a-test-v1",
      indexed_at: new Date("2026-04-09T08:00:00Z")
    });
    const chunkId = await insertDocumentChunk(created.id);

    const evidenceResponse = await app.inject({
      method: "GET",
      url: `/api/v1/documents/${created.id}/evidence`
    });

    assert.equal(evidenceResponse.statusCode, 200);
    const evidence = evidenceResponse.json();
    assert.equal(evidence.document_id, created.id);
    assert.equal(evidence.index_status, "ready");
    assert.equal(evidence.citation_ready, true);
    assert.equal(evidence.items.length, 1);
    assert.equal(evidence.items[0].chunk_id, chunkId);
    assert.equal(evidence.items[0].section_label, "章节 1");
    assert.equal(evidence.items[0].page_ref, "p. 7");
    assert.equal(evidence.items[0].quote_text, "可用于引用的证据片段。");
    assert.deepEqual(evidence.items[0].locator, { page: 7, offset: 12 });

    const reindexResponse = await app.inject({
      method: "POST",
      url: `/api/v1/documents/${created.id}/reindex`
    });

    assert.equal(reindexResponse.statusCode, 202);
    const reindexed = reindexResponse.json();
    assert.equal(reindexed.document_id, created.id);
    assert.equal(reindexed.index_status, "queued");
    assert.ok(reindexed.job_id);

    const detailResponse = await app.inject({
      method: "GET",
      url: `/api/v1/documents/${created.id}`
    });
    assert.equal(detailResponse.statusCode, 200);
    const detail = detailResponse.json();
    assert.equal(detail.index_status, "queued");
    assert.equal(detail.citation_ready, false);
    assert.equal(detail.latest_job_status, "queued");
    assert.equal(detail.latest_job.status, "queued");
  } finally {
    await app.close();
  }
});

test("documents API rejects reindex when parse is invalid or indexing is active", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const queueService = app.get(QueueService) as { enqueueChunkDocument: (documentId: string, jobId: string) => Promise<string> };
    queueService.enqueueChunkDocument = async () => "queue-job-reindex-1";

    const createResponse = await app.inject({
      method: "POST",
      url: "/api/v1/documents/text",
      payload: {
        title: "Reindex guard document",
        content: "Guarded content",
        tags: ["guard", "integration"]
      }
    });

    assert.equal(createResponse.statusCode, 201);
    const created = createResponse.json();

    await updateDocumentFailureState(created.id);
    const invalidParseResponse = await app.inject({
      method: "POST",
      url: `/api/v1/documents/${created.id}/reindex`
    });
    assert.equal(invalidParseResponse.statusCode, 400);
    assert.match(invalidParseResponse.body, /Only successfully parsed documents can be reindexed/);

    await setDocumentParseSuccess(created.id);
    await setDocumentIndexState(created.id, {
      index_status: "chunking",
      citation_ready: false,
      index_version: null,
      indexed_at: null
    });

    const activeIndexResponse = await app.inject({
      method: "POST",
      url: `/api/v1/documents/${created.id}/reindex`
    });
    assert.equal(activeIndexResponse.statusCode, 400);
    assert.match(activeIndexResponse.body, /Document indexing is already in progress/);
  } finally {
    await app.close();
  }
});
