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
      "truncate table answer_claims, answer_citations, retrieval_run_hits, retrieval_runs, answer_sessions, document_chunks, evaluation_runs, deployment_records, document_processing_events, upload_parts, document_parse_jobs, document_tags, uploads, tags, documents restart identity cascade"
    );
  } finally {
    await pool.end();
  }
}

async function seedSamplesAndCompareData() {
  const pool = new Pool({
    connectionString: databaseUrl
  });

  const deploymentRecordId = randomUUID();
  const previousDeploymentRecordId = randomUUID();
  const beforeSessionId = randomUUID();
  const afterSessionId = randomUUID();
  const afterDocumentId = randomUUID();
  const incidentDocumentId = randomUUID();
  const incidentJobId = randomUUID();
  const evaluationRunId = randomUUID();
  const now = new Date();
  const deployedAt = new Date(now.getTime() - 2 * 60 * 60 * 1000);
  const beforeAt = new Date(deployedAt.getTime() - 30 * 60 * 1000);
  const afterAt = new Date(deployedAt.getTime() + 30 * 60 * 1000);
  const incidentAt = new Date(now.getTime() - 10 * 60 * 1000);
  const previousDeployedAt = new Date(deployedAt.getTime() - 2 * 24 * 60 * 60 * 1000);
  const commitSha = "a".repeat(40);
  const previousCommitSha = "b".repeat(40);

  try {
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
        values
          ($1, 'production', $2, '24547000000', 'acr/xrag-api:current', 'acr/xrag-api:stable', 'failed', $3, $3, 'https://example.com/deploy/current', $3),
          ($4, 'production', $5, '24546000000', 'acr/xrag-api:previous', null, 'passed', $6, $6, 'https://example.com/deploy/previous', $6)
      `,
      [deploymentRecordId, commitSha, deployedAt, previousDeploymentRecordId, previousCommitSha, previousDeployedAt]
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
          groundedness,
          citation_coverage,
          created_at,
          completed_at
        )
        values ($1, 'eval-phase-3a-current', 'production', 'ci', 'completed', $2, 'phase-3a-fixture', 0.9000, 0.9100, $3, $3)
      `,
      [evaluationRunId, commitSha, afterAt]
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
          diagnosis_code,
          provider_name,
          provider_model,
          latency_ms,
          total_cost_usd,
          created_at,
          updated_at,
          finished_at
        )
        values
          ($1, 'Before deployment timeout?', 'global', null, 'hybrid', 'failed', 'provider_timeout', 'openai', 'gpt-test', 1000, '0.1000', $2, $2, $2),
          ($3, 'After deployment timeout?', 'global', null, 'hybrid', 'failed', 'provider_timeout', 'openai', 'gpt-test', 2000, '0.2000', $4, $4, $4)
      `,
      [beforeSessionId, beforeAt, afterSessionId, afterAt]
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
          citation_ready,
          diagnosis_code,
          diagnosis_summary,
          created_at,
          imported_at,
          updated_at
        )
        values
          ($1, 'After deployment embedding failure', 'text', 'manual_input', 'success', 'failed', false, 'index_embedding_failed', 'Embedding failed after deployment.', $2, $2, $2),
          ($3, 'Incident parse timeout', 'pdf', 'upload', 'failed', 'failed', false, 'pdf_parse_timeout', 'Parser timed out.', $4, $4, $4)
      `,
      [afterDocumentId, afterAt, incidentDocumentId, incidentAt]
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
        values ($1, $2, 'parse_document', 'failed', 1, 'pdf_parse_timeout', 'PDF parser timeout exceeded', 'pdf_parse_timeout', 'INC-PHASE3A-PARSE', 15000, $3, $3)
      `,
      [incidentJobId, incidentDocumentId, incidentAt]
    );

    return {
      deploymentRecordId,
      previousDeploymentRecordId,
      afterSessionId,
      afterDocumentId,
      incidentDocumentId
    };
  } finally {
    await pool.end();
  }
}

async function seedReplayData() {
  const pool = new Pool({
    connectionString: databaseUrl
  });

  const documentId = randomUUID();
  const chunkId = randomUUID();
  const sessionId = randomUUID();
  const retrievalRunId = randomUUID();
  const now = new Date();
  const importedAt = new Date(now.getTime() - 3 * 60 * 60 * 1000);
  const indexedAt = new Date(now.getTime() - 60 * 60 * 1000);

  try {
    await pool.query(
      `
        insert into documents (
          id,
          title,
          content_raw,
          content_clean,
          content_preview,
          source_type,
          source_origin,
          parse_status,
          index_status,
          index_version,
          indexed_at,
          citation_ready,
          created_at,
          imported_at,
          updated_at
        )
        values ($1, 'Replay ready document', 'Raw replay text', 'Clean replay text', 'Replay preview', 'text', 'manual_input', 'success', 'ready', 'phase-3a-test', $2, true, $3, $3, $2)
      `,
      [documentId, indexedAt, importedAt]
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
        values ($1, $2, 0, 'phase-3a-test', 'Replay section', 'p. 1', 'Replay evidence quote', 10, $3, $4, $5)
      `,
      [chunkId, documentId, "c".repeat(64), { page: 1, paragraph: 1 }, indexedAt]
    );

    await pool.query(
      `
        insert into document_processing_events (
          id,
          document_id,
          event_type,
          stage,
          status,
          diagnosis_code,
          summary,
          created_at
        )
        values ($1, $2, 'embedding_completed', 'index', 'success', null, 'Embedding completed.', $3)
      `,
      [randomUUID(), documentId, indexedAt]
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
          provider_name,
          provider_model,
          latency_ms,
          total_cost_usd,
          created_at,
          updated_at,
          finished_at
        )
        values ($1, 'Replay answer?', 'document', $2, 'hybrid', 'answered', 'Replay answer summary', 'openai', 'gpt-test', 120, '0.1200', $3, $3, $3)
      `,
      [sessionId, { document_id: documentId }, now]
    );

    await pool.query(
      `
        insert into answer_claims (
          id,
          session_id,
          claim_slot,
          display_order,
          claim_text,
          freshness_badge,
          created_at
        )
        values ($1, $2, 'claim-1', 1, 'Replay claim', 'stale_risk', $3)
      `,
      [randomUUID(), sessionId, now]
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
        values ($1, $2, $3, $4, 'claim-1', 'Replay evidence quote', $5, $6)
      `,
      [randomUUID(), sessionId, documentId, chunkId, { page: 1, paragraph: 1 }, now]
    );

    await pool.query(
      `
        insert into retrieval_runs (
          id,
          session_id,
          query_normalized,
          eligible_document_count,
          lexical_hit_count,
          semantic_hit_count,
          merged_hit_count,
          rerank_strategy,
          latency_ms,
          created_at
        )
        values ($1, $2, 'replay answer', 1, 1, 1, 1, 'hybrid', 42, $3)
      `,
      [retrievalRunId, sessionId, now]
    );

    await pool.query(
      `
        insert into retrieval_run_hits (
          id,
          retrieval_run_id,
          document_id,
          chunk_id,
          rank,
          lexical_score,
          semantic_score,
          final_score,
          used_in_answer,
          exclusion_reason,
          created_at
        )
        values ($1, $2, $3, $4, 1, 0.8000, 0.9000, 0.9500, false, 'citation_unready', $5)
      `,
      [randomUUID(), retrievalRunId, documentId, chunkId, now]
    );

    return {
      documentId,
      sessionId
    };
  } finally {
    await pool.end();
  }
}

test("phase 3A ops samples and deployment compare derive read models from existing facts", async () => {
  await resetDatabase();
  const seeded = await seedSamplesAndCompareData();
  const app = await createApp();
  await app.init();

  try {
    const trendResponse = await app.inject({
      method: "GET",
      url: "/api/v1/ops/samples?origin=trend&sample_kind=answer_session&window=24h"
    });

    assert.equal(trendResponse.statusCode, 200);
    const trend = trendResponse.json();
    assert.equal(trend.origin, "trend");
    assert.equal(trend.window, "24h");
    assert.equal(
      trend.items.some(
        (item: { sample_id: string; next_replay_ref: { path: string } }) =>
          item.sample_id === `answer_session:${seeded.afterSessionId}` &&
          item.next_replay_ref.path === `/api/v1/ops/replays/answer-sessions/${seeded.afterSessionId}`
      ),
      true
    );

    const incidentResponse = await app.inject({
      method: "GET",
      url: "/api/v1/ops/samples?origin=incident_cluster&cluster_key=parse:high:open&window=24h"
    });

    assert.equal(incidentResponse.statusCode, 200);
    const incident = incidentResponse.json();
    assert.equal(incident.items.length, 1);
    assert.equal(incident.items[0].sample_id, `document:${seeded.incidentDocumentId}`);
    assert.equal(incident.items[0].related_incident_ref, "INC-PHASE3A-PARSE");

    const releaseSamplesResponse = await app.inject({
      method: "GET",
      url: `/api/v1/ops/samples?origin=release_compare&deployment_record_id=${seeded.deploymentRecordId}&window=24h`
    });

    assert.equal(releaseSamplesResponse.statusCode, 200);
    const releaseSamples = releaseSamplesResponse.json();
    assert.equal(releaseSamples.items.length, 3);
    assert.equal(
      releaseSamples.items.some(
        (item: { sample_id: string; regression_class: string }) =>
          item.sample_id === `answer_session:${seeded.afterSessionId}` && item.regression_class === "existing_debt"
      ),
      true
    );
    assert.equal(
      releaseSamples.items.some(
        (item: { sample_id: string; regression_class: string }) =>
          item.sample_id === `document:${seeded.afterDocumentId}` && item.regression_class === "new_regression"
      ),
      true
    );

    const compareResponse = await app.inject({
      method: "GET",
      url: `/api/v1/ops/deployments/compare?deployment_record_id=${seeded.deploymentRecordId}&window=24h`
    });

    assert.equal(compareResponse.statusCode, 200);
    const compare = compareResponse.json();
    assert.equal(compare.deployment.deployment_record_id, seeded.deploymentRecordId);
    assert.equal(compare.baseline.previous_deployment_record_id, seeded.previousDeploymentRecordId);
    assert.equal(compare.baseline.related_evaluation_run_ref, "eval-phase-3a-current");
    assert.equal(compare.before_window.sample_count, 1);
    assert.equal(compare.after_window.sample_count, 3);
    assert.equal(compare.delta_summary.existing_debt_count, 1);
    assert.equal(compare.delta_summary.new_regression_count, 2);
    assert.equal(compare.delta_summary.affected_answer_session_count, 1);
    assert.equal(compare.delta_summary.affected_document_count, 2);
  } finally {
    await app.close();
  }
});

test("phase 3A ops diagnostics handle empty states, pagination, validation, and 404s", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const emptyTrendResponse = await app.inject({
      method: "GET",
      url: "/api/v1/ops/samples?origin=trend&page=1&page_size=1"
    });
    assert.equal(emptyTrendResponse.statusCode, 200);
    const emptyTrend = emptyTrendResponse.json();
    assert.equal(emptyTrend.total, 0);
    assert.equal(emptyTrend.items.length, 0);

    const invalidOriginResponse = await app.inject({
      method: "GET",
      url: "/api/v1/ops/samples?origin=not_real"
    });
    assert.equal(invalidOriginResponse.statusCode, 400);

    const missingClusterResponse = await app.inject({
      method: "GET",
      url: "/api/v1/ops/samples?origin=incident_cluster"
    });
    assert.equal(missingClusterResponse.statusCode, 400);

    const missingReleaseDeploymentResponse = await app.inject({
      method: "GET",
      url: "/api/v1/ops/samples?origin=release_compare"
    });
    assert.equal(missingReleaseDeploymentResponse.statusCode, 400);

    const missingCompareDeploymentResponse = await app.inject({
      method: "GET",
      url: "/api/v1/ops/deployments/compare"
    });
    assert.equal(missingCompareDeploymentResponse.statusCode, 400);

    const unknownCompareDeploymentResponse = await app.inject({
      method: "GET",
      url: `/api/v1/ops/deployments/compare?deployment_record_id=${randomUUID()}`
    });
    assert.equal(unknownCompareDeploymentResponse.statusCode, 404);

    const seeded = await seedSamplesAndCompareData();
    const pagedReleaseSamplesResponse = await app.inject({
      method: "GET",
      url: `/api/v1/ops/samples?origin=release_compare&deployment_record_id=${seeded.deploymentRecordId}&window=24h&page=2&page_size=1`
    });
    assert.equal(pagedReleaseSamplesResponse.statusCode, 200);
    const pagedReleaseSamples = pagedReleaseSamplesResponse.json();
    assert.equal(pagedReleaseSamples.page, 2);
    assert.equal(pagedReleaseSamples.page_size, 1);
    assert.equal(pagedReleaseSamples.total, 3);
    assert.equal(pagedReleaseSamples.items.length, 1);
  } finally {
    await app.close();
  }
});

test("phase 3A ops replays preserve answer evidence and document pipeline facts", async () => {
  await resetDatabase();
  const seeded = await seedReplayData();
  const app = await createApp();
  await app.init();

  try {
    const answerReplayResponse = await app.inject({
      method: "GET",
      url: `/api/v1/ops/replays/answer-sessions/${seeded.sessionId}`
    });

    assert.equal(answerReplayResponse.statusCode, 200);
    const answerReplay = answerReplayResponse.json();
    assert.equal(answerReplay.sample.sample_id, `answer_session:${seeded.sessionId}`);
    assert.equal(answerReplay.session.session_id, seeded.sessionId);
    assert.equal(answerReplay.retrieval.items.length, 1);
    assert.equal(answerReplay.related_context.freshness_flags.includes("stale_document"), true);
    assert.equal(answerReplay.related_context.freshness_flags.includes("citation_unready"), true);

    const documentReplayResponse = await app.inject({
      method: "GET",
      url: `/api/v1/ops/replays/documents/${seeded.documentId}`
    });

    assert.equal(documentReplayResponse.statusCode, 200);
    const documentReplay = documentReplayResponse.json();
    assert.equal(documentReplay.sample.sample_id, `document:${seeded.documentId}`);
    assert.equal(documentReplay.document.id, seeded.documentId);
    assert.equal(documentReplay.timeline.items.length, 1);
    assert.equal(documentReplay.evidence.items.length, 1);
    assert.equal(documentReplay.related_context.blocking_reason, null);
    assert.equal(documentReplay.related_context.related_answer_session_count, 1);

    const missingAnswerReplayResponse = await app.inject({
      method: "GET",
      url: `/api/v1/ops/replays/answer-sessions/${randomUUID()}`
    });
    assert.equal(missingAnswerReplayResponse.statusCode, 404);

    const missingDocumentReplayResponse = await app.inject({
      method: "GET",
      url: `/api/v1/ops/replays/documents/${randomUUID()}`
    });
    assert.equal(missingDocumentReplayResponse.statusCode, 404);
  } finally {
    await app.close();
  }
});
