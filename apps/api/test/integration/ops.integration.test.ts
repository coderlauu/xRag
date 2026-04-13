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
      "truncate table answer_claims, answer_citations, retrieval_run_hits, retrieval_runs, answer_sessions, document_chunks, document_processing_events, upload_parts, document_parse_jobs, document_tags, uploads, tags, documents restart identity cascade"
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
  const readyDocumentId = randomUUID();
  const queuedDocumentId = randomUUID();
  const chunkingDocumentId = randomUUID();
  const embeddingDocumentId = randomUUID();
  const staleDocumentId = randomUUID();
  const readyChunkId = randomUUID();
  const answeredSessionId = randomUUID();
  const needsScopeSessionId = randomUUID();
  const refusedSessionId = randomUUID();
  const failedSessionId = randomUUID();
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
          index_status,
          diagnosis_code,
          diagnosis_summary,
          upload_status,
          file_name,
          mime_type,
          created_at,
          imported_at,
          updated_at
        )
        values ($1, $2, 'file', 'upload', 'failed', 'failed', 'pdf_parse_timeout', 'PDF 解析超时', 'failed', 'ops-board.pdf', 'application/pdf', $3, $3, $3)
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

    await pool.query(
      `
        insert into documents (
          id,
          title,
          source_type,
          source_origin,
          parse_status,
          index_status,
          index_version,
          indexed_at,
          citation_ready,
          content_preview,
          search_text,
          diagnosis_code,
          created_at,
          imported_at,
          updated_at
        )
        values
          ($1, 'Ready answer document', 'text', 'manual_input', 'success', 'ready', 'phase-2a-test', $2, true, 'Ready answer document preview', 'ready answer document', null, $3, $3, $3),
          ($4, 'Queued answer document', 'text', 'manual_input', 'success', 'queued', null, null, false, 'Queued answer document preview', 'queued answer document', null, $3, $3, $3),
          ($5, 'Chunking answer document', 'text', 'manual_input', 'success', 'chunking', null, null, false, 'Chunking answer document preview', 'chunking answer document', null, $3, $3, $3),
          ($6, 'Embedding answer document', 'text', 'manual_input', 'success', 'embedding', null, null, false, 'Embedding answer document preview', 'embedding answer document', null, $3, $3, $3),
          ($7, 'Stale answer document', 'text', 'manual_input', 'success', 'stale', null, null, false, 'Stale answer document preview', 'stale answer document', null, $3, $3, $3)
      `,
      [readyDocumentId, now, now, queuedDocumentId, chunkingDocumentId, embeddingDocumentId, staleDocumentId]
    );

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
        values ($1, $2, 0, 'phase-2a-test', 'Evidence section', 'p. 2', 'Ready evidence quote', 11, $3, $4, $5)
      `,
      [readyChunkId, readyDocumentId, "d".repeat(64), { page: 2, paragraph: 1 }, now]
    );

    await pool.query(
      `
        insert into answer_sessions (
          id,
          question,
          scope_mode,
          scope_payload,
          retrieval_mode,
          status,
          answer_summary,
          refusal_reason,
          diagnosis_code,
          provider_name,
          provider_model,
          latency_ms,
          prompt_tokens,
          completion_tokens,
          total_cost_usd,
          created_at,
          updated_at,
          finished_at
        )
        values
          ($1, 'What is ready?', 'search_result', $2, 'hybrid', 'answered', 'Ready answer', null, null, 'openai', 'gpt-test', 100, 10, 20, '1.0000', $3, $3, $3),
          ($4, 'What needs scope?', 'search_result', $2, 'hybrid', 'needs_scope', null, 'Need narrower scope', null, 'openai', 'gpt-test', 200, 11, 21, '2.0000', $3, $3, $3),
          ($5, 'Why refused?', 'search_result', $2, 'hybrid', 'refused', null, 'No evidence', null, 'openai', 'gpt-test', 300, 12, 22, '3.0000', $3, $3, $3),
          ($6, 'Why failed?', 'search_result', $2, 'hybrid', 'failed', null, null, 'provider_timeout', 'openai', 'gpt-test', 9999, 13, 23, '9999.0000', $3, $3, $3)
      `,
      [
        answeredSessionId,
        { document_ids: [readyDocumentId], truncated: false },
        now,
        needsScopeSessionId,
        refusedSessionId,
        failedSessionId
      ]
    );

    await pool.query(
      `
        insert into answer_citations (
          id,
          session_id,
          document_id,
          chunk_id,
          claim_slot,
          quote_text,
          locator,
          created_at
        )
        values ($1, $2, $3, $4, 'claim-1', 'Ready answer evidence', $5, $6)
      `,
      [randomUUID(), answeredSessionId, readyDocumentId, readyChunkId, { page: 2, paragraph: 1 }, now]
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

    const answerSummaryResponse = await app.inject({
      method: "GET",
      url: "/api/v1/ops/answer-summary"
    });

    assert.equal(answerSummaryResponse.statusCode, 200);
    const answerSummary = answerSummaryResponse.json();
    assert.equal(answerSummary.embedding_backlog, 3);
    assert.equal(answerSummary.ready_document_count, 1);
    assert.equal(answerSummary.stale_document_count, 1);
    assert.equal(answerSummary.failed_document_count, 1);
    assert.equal(answerSummary.citation_coverage, 1);
    assert.equal(answerSummary.refusal_rate, 0.3333);
    assert.equal(answerSummary.answer_latency_p95, 290);
    assert.equal(answerSummary.avg_token_cost_usd, "2.0000");
  } finally {
    await app.close();

    process.env.XRAG_IMAGE_TAG = currentImageTag;
    process.env.XRAG_PREVIOUS_IMAGE_TAG = previousImageTag;
    process.env.XRAG_LAST_SMOKE_STATUS = lastSmokeStatus;
    process.env.XRAG_LAST_SMOKE_AT = lastSmokeAt;
  }
});
