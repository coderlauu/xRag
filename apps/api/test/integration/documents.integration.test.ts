import test from "node:test";
import assert from "node:assert/strict";
import { Pool } from "pg";
import { createApp } from "../../src/bootstrap";

const databaseUrl = process.env.DATABASE_URL || "postgresql://xrag:xrag@127.0.0.1:5432/xrag";

async function resetDatabase() {
  const pool = new Pool({
    connectionString: databaseUrl
  });

  try {
    await pool.query(
      "truncate table document_parse_jobs, document_tags, uploads, tags, documents restart identity cascade"
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
