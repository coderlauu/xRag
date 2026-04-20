import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { Pool } from "pg";
import { createApp } from "../../src/bootstrap";

const databaseUrl = process.env.DATABASE_URL || "postgresql://xrag:xrag@127.0.0.1:5432/xrag";

function startOfUtcDay(date: Date) {
  return new Date(Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate()));
}

async function resetDatabase() {
  const pool = new Pool({
    connectionString: databaseUrl
  });

  try {
    await pool.query(
      "truncate table answer_claims, answer_citations, retrieval_run_hits, retrieval_runs, answer_sessions, document_chunks, evaluation_runs, deployment_records, document_processing_events, upload_parts, document_parse_jobs, document_tags, uploads, tags, documents restart identity cascade"
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
  const olderAnsweredSessionId = randomUUID();
  const latestEvaluationRunId = randomUUID();
  const previousEvaluationRunId = randomUUID();
  const deploymentRecordId = randomUUID();
  const now = new Date();
  const readyImportedAt = new Date(now.getTime() - 2 * 60 * 60 * 1000);
  const readyIndexedAt = new Date(now.getTime() - 60 * 60 * 1000);
  const threeDaysAgo = new Date(now.getTime() - 3 * 24 * 60 * 60 * 1000);
  const twoDaysAgo = new Date(now.getTime() - 2 * 24 * 60 * 60 * 1000);
  const yesterday = new Date(now.getTime() - 24 * 60 * 60 * 1000);
  const deployedAt = new Date(now.getTime() - 90 * 60 * 1000);
  const smokeAt = new Date(now.getTime() - 60 * 60 * 1000);
  const latestCommitSha = "f".repeat(40);
  const previousCommitSha = "e".repeat(40);

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
          ($1, 'Ready answer document', 'text', 'manual_input', 'success', 'ready', 'phase-2a-test', $2, true, 'Ready answer document preview', 'ready answer document', null, $3, $4, $3),
          ($5, 'Queued answer document', 'text', 'manual_input', 'success', 'queued', null, null, false, 'Queued answer document preview', 'queued answer document', null, $3, $3, $3),
          ($6, 'Chunking answer document', 'text', 'manual_input', 'success', 'chunking', null, null, false, 'Chunking answer document preview', 'chunking answer document', null, $3, $3, $3),
          ($7, 'Embedding answer document', 'text', 'manual_input', 'success', 'embedding', null, null, false, 'Embedding answer document preview', 'embedding answer document', null, $3, $3, $3),
          ($8, 'Stale answer document', 'text', 'manual_input', 'success', 'stale', null, null, false, 'Stale answer document preview', 'stale answer document', null, $3, $3, $3)
      `,
      [
        readyDocumentId,
        readyIndexedAt,
        now,
        readyImportedAt,
        queuedDocumentId,
        chunkingDocumentId,
        embeddingDocumentId,
        staleDocumentId
      ]
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
          ($6, 'Why failed?', 'search_result', $2, 'hybrid', 'failed', null, null, 'provider_timeout', 'openai', 'gpt-test', 9999, 13, 23, '9999.0000', $3, $3, $3),
          ($7, 'What was ready before?', 'search_result', $2, 'hybrid', 'answered', 'Older answer', null, null, 'openai', 'gpt-test', 150, 9, 19, '0.7500', $8, $8, $8)
      `,
      [
        answeredSessionId,
        { document_ids: [readyDocumentId], truncated: false },
        now,
        needsScopeSessionId,
        refusedSessionId,
        failedSessionId,
        olderAnsweredSessionId,
        threeDaysAgo
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
        values
          ($1, $2, $3, $4, 'claim-1', 'Ready answer evidence', $5, $6),
          ($7, $8, $3, $4, 'claim-1', 'Older ready answer evidence', $5, $9)
      `,
      [
        randomUUID(),
        answeredSessionId,
        readyDocumentId,
        readyChunkId,
        { page: 2, paragraph: 1 },
        now,
        randomUUID(),
        olderAnsweredSessionId,
        threeDaysAgo
      ]
    );

    await pool.query(
      `
        insert into evaluation_runs (
          id,
          run_ref,
          environment,
          source,
          status,
          commit_sha,
          dataset_version,
          recall_at_10,
          mrr,
          hit_in_answer_rate,
          groundedness,
          citation_coverage,
          refusal_precision,
          latency_p95_ms,
          avg_token_cost_usd,
          embedding_backlog,
          freshness_lag_p95_ms,
          artifact_url,
          created_at,
          completed_at
        )
        values
          ($1, 'eval-prod-001', 'production', 'nightly', 'completed', $2, 'dataset-v1', 0.8100, 0.7100, 0.6100, 0.9100, 0.9200, 0.9000, 220, 0.3100, 6, 21600000, 'https://example.com/eval/1', $3, $3),
          ($4, 'eval-prod-002', 'production', 'ci', 'completed', $5, 'dataset-v2', 0.8500, 0.8000, 0.7500, 0.9700, 0.9600, 0.9400, 180, 0.4200, 2, 7200000, 'https://example.com/eval/2', $6, $6)
      `,
      [previousEvaluationRunId, previousCommitSha, twoDaysAgo, latestEvaluationRunId, latestCommitSha, yesterday]
    );

    await pool.query(
      `
        insert into deployment_records (
          id,
          environment,
          commit_sha,
          workflow_run_id,
          current_image_tag,
          previous_stable_image_tag,
          smoke_status,
          smoke_at,
          deployed_at,
          evidence_url,
          created_at
        )
        values ($1, 'production', $2, '24514690725', 'acr/xrag-api:prod-sha', 'acr/xrag-api:stable', 'failed', $3, $4, 'https://example.com/deploy/24514690725', $5)
      `,
      [deploymentRecordId, latestCommitSha, smokeAt, deployedAt, deployedAt]
    );

    return {
      now,
      threeDaysAgo,
      twoDaysAgo,
      yesterday
    };
  } finally {
    await pool.end();
  }
}

test("ops API exposes live health, incident aggregation, and deployment summary", async () => {
  await resetDatabase();
  const seeded = await seedOpsData();

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
    assert.equal(answerSummary.refusal_rate, 0.25);
    assert.equal(answerSummary.answer_latency_p95, 285);
    assert.equal(answerSummary.avg_token_cost_usd, "1.6875");

    const overviewResponse = await app.inject({
      method: "GET",
      url: "/api/v1/ops/overview"
    });

    assert.equal(overviewResponse.statusCode, 200);
    const overview = overviewResponse.json();
    assert.equal(overview.readiness.ready_count, 1);
    assert.equal(overview.readiness.queued_count, 1);
    assert.equal(overview.readiness.chunking_count, 1);
    assert.equal(overview.readiness.embedding_count, 1);
    assert.equal(overview.readiness.stale_count, 1);
    assert.equal(overview.readiness.failed_count, 1);
    assert.equal(overview.readiness.total_count, 6);
    assert.equal(overview.readiness.readiness_rate, 0.1667);
    assert.equal(overview.readiness.freshness_lag_p95_ms, 3600000);
    assert.equal(overview.readiness.blocking_reason, "indexing_failed");
    assert.equal(overview.runtime_quality.window, "24h");
    assert.equal(overview.runtime_quality.terminal_session_count, 3);
    assert.equal(overview.runtime_quality.answered_session_count, 1);
    assert.equal(overview.runtime_quality.latency_p50_ms, 200);
    assert.equal(overview.runtime_quality.latency_p95_ms, 290);
    assert.equal(overview.runtime_quality.citation_coverage, 1);
    assert.equal(overview.runtime_quality.refusal_rate, 0.3333);
    assert.equal(overview.runtime_quality.avg_token_cost_usd, "2.0000");
    assert.equal(overview.evaluation_quality.latest_run_ref, "eval-prod-002");
    assert.equal(overview.evaluation_quality.environment, "production");
    assert.equal(overview.evaluation_quality.source, "ci");
    assert.equal(overview.evaluation_quality.commit_sha, "f".repeat(40));
    assert.equal(overview.evaluation_quality.groundedness, 0.97);
    assert.equal(overview.evaluation_quality.citation_coverage, 0.96);
    assert.equal(overview.evaluation_quality.refusal_precision, 0.94);
    assert.equal(overview.evaluation_quality.avg_token_cost_usd, "0.4200");
    assert.equal(overview.incident_summary.open_count, 2);
    assert.equal(overview.incident_summary.high_risk_count, 2);
    assert.equal(
      overview.incident_summary.clusters.some(
        (cluster: { source: string; severity: string; incident_count: number }) =>
          cluster.source === "parse" && cluster.severity === "high" && cluster.incident_count === 1
      ),
      true
    );
    assert.equal(overview.release_guard.current_image_tag, "acr/xrag-api:prod-sha");
    assert.equal(overview.release_guard.previous_stable_image_tag, "acr/xrag-api:stable");
    assert.equal(overview.release_guard.smoke_status, "failed");
    assert.equal(overview.release_guard.risk_level, "critical");
    assert.equal(overview.release_guard.workflow_run_id, "24514690725");
    assert.equal(overview.release_guard.related_evaluation_run_ref, "eval-prod-002");
    assert.equal(overview.release_guard.related_incident_count, 2);
    assert.equal(
      overview.recommended_actions.some((action: { code: string }) => action.code === "inspect_failed_documents"),
      true
    );
    assert.equal(
      overview.recommended_actions.some((action: { code: string }) => action.code === "inspect_incident_cluster"),
      true
    );
    assert.equal(
      overview.recommended_actions.some((action: { code: string }) => action.code === "rollback_to_previous_stable"),
      true
    );
    assert.equal(
      overview.notices.some((notice: { target: string; code: string }) => notice.target === "ask" && notice.code === "indexing_failed"),
      true
    );
    assert.equal(
      overview.notices.some(
        (notice: { target: string; code: string }) => notice.target === "ask" && notice.code === "rollback_to_previous_stable"
      ),
      true
    );

    const trendsResponse = await app.inject({
      method: "GET",
      url: "/api/v1/ops/trends?window=7d"
    });

    assert.equal(trendsResponse.statusCode, 200);
    const trends = trendsResponse.json();
    assert.equal(trends.window, "7d");
    assert.equal(trends.series.length, 11);

    const currentDayBucket = startOfUtcDay(seeded.now).toISOString();
    const olderRuntimeBucket = startOfUtcDay(seeded.threeDaysAgo).toISOString();
    const latestEvaluationBucket = startOfUtcDay(seeded.yesterday).toISOString();
    const previousEvaluationBucket = startOfUtcDay(seeded.twoDaysAgo).toISOString();

    const runtimeCitationCoverage = trends.series.find(
      (series: { metric: string; source: string }) => series.metric === "citation_coverage" && series.source === "runtime"
    );
    assert.deepEqual(
      runtimeCitationCoverage.points.map((point: { ts: string; value: number }) => ({ ts: point.ts, value: point.value })),
      [
        { ts: olderRuntimeBucket, value: 1 },
        { ts: currentDayBucket, value: 1 }
      ]
    );

    const runtimeRefusalRate = trends.series.find(
      (series: { metric: string; source: string }) => series.metric === "refusal_rate" && series.source === "runtime"
    );
    assert.deepEqual(
      runtimeRefusalRate.points.map((point: { ts: string; value: number }) => ({ ts: point.ts, value: point.value })),
      [
        { ts: olderRuntimeBucket, value: 0 },
        { ts: currentDayBucket, value: 0.3333 }
      ]
    );

    const runtimeLatency = trends.series.find(
      (series: { metric: string; source: string }) => series.metric === "latency_p95_ms" && series.source === "runtime"
    );
    assert.deepEqual(
      runtimeLatency.points.map((point: { ts: string; value: number }) => ({ ts: point.ts, value: point.value })),
      [
        { ts: olderRuntimeBucket, value: 150 },
        { ts: currentDayBucket, value: 290 }
      ]
    );

    const runtimeCost = trends.series.find(
      (series: { metric: string; source: string }) => series.metric === "avg_token_cost_usd" && series.source === "runtime"
    );
    assert.deepEqual(
      runtimeCost.points.map((point: { ts: string; value: string }) => ({ ts: point.ts, value: point.value })),
      [
        { ts: olderRuntimeBucket, value: "0.7500" },
        { ts: currentDayBucket, value: "2.0000" }
      ]
    );

    const evaluationGroundedness = trends.series.find(
      (series: { metric: string; source: string }) => series.metric === "groundedness" && series.source === "evaluation"
    );
    assert.deepEqual(
      evaluationGroundedness.points.map((point: { ts: string; value: number }) => ({ ts: point.ts, value: point.value })),
      [
        { ts: previousEvaluationBucket, value: 0.91 },
        { ts: latestEvaluationBucket, value: 0.97 }
      ]
    );

    const evaluationBacklog = trends.series.find(
      (series: { metric: string; source: string }) => series.metric === "embedding_backlog" && series.source === "evaluation"
    );
    assert.deepEqual(
      evaluationBacklog.points.map((point: { ts: string; value: number }) => ({ ts: point.ts, value: point.value })),
      [
        { ts: previousEvaluationBucket, value: 6 },
        { ts: latestEvaluationBucket, value: 2 }
      ]
    );

    const evaluationFreshnessLag = trends.series.find(
      (series: { metric: string; source: string }) => series.metric === "freshness_lag_p95_ms" && series.source === "evaluation"
    );
    assert.deepEqual(
      evaluationFreshnessLag.points.map((point: { ts: string; value: number }) => ({ ts: point.ts, value: point.value })),
      [
        { ts: previousEvaluationBucket, value: 21600000 },
        { ts: latestEvaluationBucket, value: 7200000 }
      ]
    );
  } finally {
    await app.close();

    process.env.XRAG_IMAGE_TAG = currentImageTag;
    process.env.XRAG_PREVIOUS_IMAGE_TAG = previousImageTag;
    process.env.XRAG_LAST_SMOKE_STATUS = lastSmokeStatus;
    process.env.XRAG_LAST_SMOKE_AT = lastSmokeAt;
  }
});

test("ops health warns when stale worker-owned work exceeds the liveness window", async () => {
  await resetDatabase();
  const pool = new Pool({
    connectionString: databaseUrl
  });
  const staleDocumentId = randomUUID();
  const staleAnswerSessionId = randomUUID();
  const staleDocumentJobId = randomUUID();
  const staleAt = new Date(Date.now() - 11 * 60 * 1000);

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
          created_at,
          imported_at,
          updated_at
        )
        values ($1, 'Stale queue document', 'text', 'manual_input', 'pending', 'queued', $2, $2, $2)
      `,
      [staleDocumentId, staleAt]
    );

    await pool.query(
      `
        insert into document_parse_jobs (
          id,
          document_id,
          job_type,
          status,
          attempt,
          created_at
        )
        values ($1, $2, 'parse_document', 'queued', 1, $3)
      `,
      [staleDocumentJobId, staleDocumentId, staleAt]
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
          created_at,
          updated_at
        )
        values ($1, 'Why is worker stuck?', 'global', null, 'hybrid', 'retrieving', $2, $2)
      `,
      [staleAnswerSessionId, staleAt]
    );
  } finally {
    await pool.end();
  }

  const app = await createApp();
  await app.init();

  try {
    const healthResponse = await app.inject({
      method: "GET",
      url: "/api/v1/ops/health-summary"
    });

    assert.equal(healthResponse.statusCode, 200);
    const health = healthResponse.json();
    const worker = health.services.find((service: { name: string }) => service.name === "worker");
    assert.equal(worker.status, "warning");
    assert.match(worker.detail, /1 stale active answer session/);
    assert.match(worker.detail, /1 stale queued\/running document job/);
  } finally {
    await app.close();
  }
});
