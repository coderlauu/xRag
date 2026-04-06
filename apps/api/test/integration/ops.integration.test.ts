import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
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

async function seedOpsData() {
  const pool = new Pool({
    connectionString: databaseUrl
  });

  const documentId = randomUUID();
  const uploadId = randomUUID();
  const parseJobId = randomUUID();
  const now = new Date();

  try {
    await pool.query(
      `
        insert into documents (
          id,
          title,
          source_type,
          source_origin,
          parse_status,
          diagnosis_code,
          diagnosis_summary,
          upload_status,
          file_name,
          mime_type,
          created_at,
          imported_at,
          updated_at
        )
        values ($1, $2, 'file', 'upload', 'failed', 'pdf_parse_timeout', 'PDF 解析超时', 'failed', 'ops-board.pdf', 'application/pdf', $3, $3, $3)
      `,
      [documentId, "Ops failure document", now]
    );

    await pool.query(
      `
        insert into uploads (
          id,
          file_name,
          mime_type,
          file_size,
          object_key,
          checksum_sha256,
          upload_mode,
          status,
          error_code,
          error_message,
          created_at,
          completed_at
        )
        values ($1, 'broken-upload.pdf', 'application/pdf', 4096, 'uploads/broken-upload.pdf', $2, 'multipart', 'failed', 'object_missing_on_complete', '对象校验失败，未找到完整文件。', $3, $3)
      `,
      [uploadId, "c".repeat(64), now]
    );

    await pool.query(
      `
        insert into document_parse_jobs (
          id,
          document_id,
          job_type,
          status,
          attempt,
          error_code,
          error_message,
          diagnosis_code,
          incident_ref,
          runtime_ms,
          finished_at,
          created_at
        )
        values ($1, $2, 'parse_document', 'failed', 1, 'pdf_parse_timeout', 'PDF parser timeout exceeded', 'pdf_parse_timeout', 'INC-PDF-001', 15000, $3, $3)
      `,
      [parseJobId, documentId, now]
    );
  } finally {
    await pool.end();
  }
}

test("ops API exposes live health, incident aggregation, and deployment summary", async () => {
  await resetDatabase();
  await seedOpsData();

  const previousImageTag = process.env.XRAG_PREVIOUS_IMAGE_TAG;
  const currentImageTag = process.env.XRAG_IMAGE_TAG;
  const lastSmokeStatus = process.env.XRAG_LAST_SMOKE_STATUS;
  const lastSmokeAt = process.env.XRAG_LAST_SMOKE_AT;

  process.env.XRAG_IMAGE_TAG = "acr/xrag-api:test-sha";
  process.env.XRAG_PREVIOUS_IMAGE_TAG = "acr/xrag-api:prev";
  process.env.XRAG_LAST_SMOKE_STATUS = "passed";
  process.env.XRAG_LAST_SMOKE_AT = "2026-04-06T13:40:00Z";

  const app = await createApp();
  await app.init();

  try {
    const healthResponse = await app.inject({
      method: "GET",
      url: "/api/v1/ops/health-summary"
    });

    assert.equal(healthResponse.statusCode, 200);
    const health = healthResponse.json();
    assert.equal(Array.isArray(health.services), true);
    assert.equal(health.services.some((service: { name: string }) => service.name === "api"), true);

    const incidentsResponse = await app.inject({
      method: "GET",
      url: "/api/v1/ops/incidents"
    });

    assert.equal(incidentsResponse.statusCode, 200);
    const incidents = incidentsResponse.json();
    assert.equal(incidents.items.length >= 2, true);
    assert.equal(
      incidents.items.some((item: { incident_ref: string; title: string }) => item.incident_ref === "INC-PDF-001" && item.title === "PDF 解析超时"),
      true
    );
    assert.equal(
      incidents.items.some((item: { source: string; title: string }) => item.source === "upload" && item.title === "对象存储缺少上传对象"),
      true
    );

    const deploymentResponse = await app.inject({
      method: "GET",
      url: "/api/v1/ops/deployments/latest"
    });

    assert.equal(deploymentResponse.statusCode, 200);
    const deployment = deploymentResponse.json();
    assert.equal(deployment.current_image_tag, "acr/xrag-api:test-sha");
    assert.equal(deployment.previous_stable_image_tag, "acr/xrag-api:prev");
    assert.equal(deployment.last_smoke_status, "passed");
    assert.equal(deployment.last_smoke_at, "2026-04-06T13:40:00Z");
  } finally {
    await app.close();

    process.env.XRAG_IMAGE_TAG = currentImageTag;
    process.env.XRAG_PREVIOUS_IMAGE_TAG = previousImageTag;
    process.env.XRAG_LAST_SMOKE_STATUS = lastSmokeStatus;
    process.env.XRAG_LAST_SMOKE_AT = lastSmokeAt;
  }
});
