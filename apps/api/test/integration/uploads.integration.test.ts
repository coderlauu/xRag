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
      "truncate table upload_parts, document_parse_jobs, document_tags, uploads, tags, documents restart identity cascade"
    );
  } finally {
    await pool.end();
  }
}

test("uploads API exposes the Phase 1B single-upload contract and document projection", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const content = "phase 1b single upload integration";
    const initiateResponse = await app.inject({
      method: "POST",
      url: "/api/v1/uploads/initiate",
      payload: {
        file_name: "phase-1b-upload.txt",
        mime_type: "text/plain",
        file_size: content.length,
        checksum_sha256: "a".repeat(64)
      }
    });

    assert.equal(initiateResponse.statusCode, 201);
    const initiated = initiateResponse.json();
    assert.equal(initiated.upload_mode, "single");
    assert.equal(initiated.status, "initiated");
    assert.ok(initiated.upload_url);

    const putResponse = await fetch(initiated.upload_url, {
      method: "PUT",
      headers: {
        "content-type": "text/plain"
      },
      body: content
    });
    assert.equal(putResponse.ok, true);

    const completeResponse = await app.inject({
      method: "POST",
      url: `/api/v1/uploads/${initiated.upload_id}/complete`,
      payload: {
        title: "Phase 1B upload integration",
        tags: ["integration", "upload"],
        checksum_sha256: "a".repeat(64)
      }
    });

    assert.equal(completeResponse.statusCode, 201);
    const completed = completeResponse.json();
    assert.equal(completed.upload_id, initiated.upload_id);
    assert.equal(completed.upload_status, "uploaded");
    assert.equal(completed.parse_status, "pending");
    assert.equal(completed.diagnosis_code, null);

    const detailResponse = await app.inject({
      method: "GET",
      url: `/api/v1/documents/${completed.document_id}`
    });
    assert.equal(detailResponse.statusCode, 200);
    const detail = detailResponse.json();
    assert.equal(detail.upload_status, "uploaded");
    assert.equal(detail.latest_job_status, "queued");
    assert.equal(detail.upload.upload_mode, "single");
    assert.equal(detail.upload.status, "uploaded");
    assert.equal(detail.latest_job.status, "queued");
  } finally {
    await app.close();
  }
});

test("uploads API exposes multipart session URLs and part completion contract", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const initiateResponse = await app.inject({
      method: "POST",
      url: "/api/v1/uploads/initiate",
      payload: {
        file_name: "phase-1b-large.pdf",
        mime_type: "application/pdf",
        file_size: 11 * 1024 * 1024
      }
    });

    assert.equal(initiateResponse.statusCode, 201);
    const initiated = initiateResponse.json();
    assert.equal(initiated.upload_mode, "multipart");
    assert.ok(initiated.part_count >= 3);
    assert.ok(initiated.provider_upload_id);

    const partsResponse = await app.inject({
      method: "POST",
      url: `/api/v1/uploads/${initiated.upload_id}/parts`,
      payload: {
        part_numbers: [1, 2]
      }
    });

    assert.equal(partsResponse.statusCode, 201);
    const parts = partsResponse.json();
    assert.equal(parts.parts.length, 2);
    assert.equal(parts.parts[0].part_number, 1);
    assert.ok(parts.parts[0].upload_url);

    const partCompleteResponse = await app.inject({
      method: "POST",
      url: `/api/v1/uploads/${initiated.upload_id}/parts/1/complete`,
      payload: {
        etag: "\"etag-1\"",
        size_bytes: 5 * 1024 * 1024
      }
    });

    assert.equal(partCompleteResponse.statusCode, 201);
    const completedPart = partCompleteResponse.json();
    assert.equal(completedPart.status, "uploaded");
    assert.equal(completedPart.uploaded_part_count, 1);
  } finally {
    await app.close();
  }
});
