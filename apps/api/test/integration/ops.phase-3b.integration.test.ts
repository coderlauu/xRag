import test from "node:test";
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { Pool } from "pg";
import { createApp } from "../../src/bootstrap";
import { QueueService } from "../../src/queue/queue.service";

const databaseUrl = process.env.DATABASE_URL || "postgresql://xrag:xrag@127.0.0.1:5432/xrag";

async function resetDatabase() {
  await withPool(async (pool) => {
    await pool.query(
      "truncate table operator_recovery_actions, answer_claims, answer_citations, retrieval_run_hits, retrieval_runs, answer_sessions, document_chunks, evaluation_runs, deployment_records, document_processing_events, upload_parts, document_parse_jobs, document_tags, uploads, tags, documents restart identity cascade"
    );
  });
}

async function seedRecoveryData() {
  const deploymentRecordId = randomUUID();
  const previousDeploymentRecordId = randomUUID();
  const answerSessionId = randomUUID();
  const reindexDocumentId = randomUUID();
  const retryDocumentId = randomUUID();
  const retryJobId = randomUUID();
  const now = new Date();
  const deployedAt = new Date(now.getTime() - 2 * 60 * 60 * 1000);
  const previousDeployedAt = new Date(deployedAt.getTime() - 2 * 24 * 60 * 60 * 1000);
  const afterAt = new Date(deployedAt.getTime() + 30 * 60 * 1000);

  await withPool(async (pool) => {
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
          ($1, 'production', $2, '24588000000', 'acr/xrag-api:phase-3b', 'acr/xrag-api:stable', 'failed', $3, $3, 'https://example.com/deploy/phase-3b', $3),
          ($4, 'production', $5, '24587000000', 'acr/xrag-api:previous', null, 'passed', $6, $6, 'https://example.com/deploy/previous', $6)
      `,
      [deploymentRecordId, "c".repeat(40), deployedAt, previousDeploymentRecordId, "d".repeat(40), previousDeployedAt]
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
        values ($1, 'Why did recovery answer fail?', 'global', null, 'hybrid', 'failed', 'provider_timeout', 'openai', 'gpt-test', 4000, '0.4000', $2, $2, $2)
      `,
      [answerSessionId, afterAt]
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
          ($1, 'Embedding failure document', 'text', 'manual_input', 'success', 'failed', false, 'index_embedding_failed', 'Embedding failed after deployment.', $3, $3, $3),
          ($2, 'Parse retry document', 'pdf', 'upload', 'failed', 'failed', false, 'pdf_parse_timeout', 'Parser timed out after deployment.', $3, $3, $3)
      `,
      [reindexDocumentId, retryDocumentId, afterAt]
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
        values ($1, $2, 'parse_document', 'failed', 1, 'pdf_parse_timeout', 'PDF parser timeout exceeded', 'pdf_parse_timeout', 'INC-PHASE3B-PARSE', 15000, $3, $3)
      `,
      [retryJobId, retryDocumentId, afterAt]
    );
  });

  return {
    deploymentRecordId,
    previousDeploymentRecordId,
    answerSessionId,
    reindexDocumentId,
    retryDocumentId
  };
}

async function withPool<T>(callback: (pool: Pool) => Promise<T>): Promise<T> {
  const pool = new Pool({
    connectionString: databaseUrl
  });

  try {
    return await callback(pool);
  } finally {
    await pool.end();
  }
}

function getJobIdFromRef(ref: { path: string }) {
  const parts = ref.path.split("/");
  return parts[parts.length - 1] || "";
}

async function markRecoveryActionRunning(jobId: string) {
  const startedAt = new Date();

  await withPool(async (pool) => {
    await pool.query(
      `
        update document_parse_jobs
        set status = 'running',
            started_at = $2
        where id = $1
      `,
      [jobId, startedAt]
    );
  });
}

async function markReindexRecoverySucceeded(documentId: string, rootJobId: string) {
  const embedJobId = randomUUID();
  const base = new Date();
  const rootStartedAt = new Date(base.getTime() + 1000);
  const rootFinishedAt = new Date(base.getTime() + 2000);
  const embedStartedAt = new Date(base.getTime() + 3000);
  const embedFinishedAt = new Date(base.getTime() + 4000);

  await withPool(async (pool) => {
    await pool.query(
      `
        update document_parse_jobs
        set status = 'succeeded',
            started_at = $2,
            finished_at = $3
        where id = $1
      `,
      [rootJobId, rootStartedAt, rootFinishedAt]
    );

    await pool.query(
      `
        insert into document_parse_jobs (
          id,
          document_id,
          queue_job_id,
          job_type,
          status,
          attempt,
          started_at,
          finished_at,
          created_at
        )
        values ($1, $2, 'queue-job-embed-1', 'embed_document', 'succeeded', 2, $3, $4, $3)
      `,
      [embedJobId, documentId, embedStartedAt, embedFinishedAt]
    );

    await pool.query(
      `
        update documents
        set index_status = 'ready',
            citation_ready = true,
            diagnosis_code = null,
            diagnosis_summary = null,
            indexed_at = $2,
            updated_at = $2
        where id = $1
      `,
      [documentId, embedFinishedAt]
    );
  });

  return embedJobId;
}

test("phase 3B recovery candidates derive from deployment compare and preview existing facts", async () => {
  await resetDatabase();
  const seeded = await seedRecoveryData();
  const app = await createApp();
  await app.init();

  try {
    const candidatesResponse = await app.inject({
      method: "GET",
      url: `/api/v1/ops/recovery/candidates?source_type=deployment_compare&source_ref=${seeded.deploymentRecordId}&page_size=10`
    });

    assert.equal(candidatesResponse.statusCode, 200);
    const candidates = candidatesResponse.json();
    assert.equal(candidates.total, 3);

    const answerCandidate = candidates.items.find(
      (item: { action_type: string; target_refs: Array<{ id: string }> }) =>
        item.action_type === "answer_diagnostic_rerun" && item.target_refs[0].id === seeded.answerSessionId
    );
    assert.ok(answerCandidate);
    assert.equal(answerCandidate.source_type, "deployment_compare");
    assert.equal(answerCandidate.source_ref, seeded.deploymentRecordId);
    assert.equal(answerCandidate.recommendation_state, "recommended");

    const reindexCandidate = candidates.items.find(
      (item: { action_type: string; target_refs: Array<{ id: string }> }) =>
        item.action_type === "document_reindex" && item.target_refs[0].id === seeded.reindexDocumentId
    );
    assert.ok(reindexCandidate);
    assert.equal(reindexCandidate.risk_level, "high");

    const retryCandidate = candidates.items.find(
      (item: { action_type: string; target_refs: Array<{ id: string }> }) =>
        item.action_type === "document_retry" && item.target_refs[0].id === seeded.retryDocumentId
    );
    assert.ok(retryCandidate);
    assert.equal(retryCandidate.blocked_reason, null);

    const previewBody = {
      candidate_id: reindexCandidate.candidate_id,
      action_type: "document_reindex",
      target_type: "document",
      target_refs: [{ type: "document", id: seeded.reindexDocumentId }]
    };

    const previewResponse = await app.inject({
      method: "POST",
      url: "/api/v1/ops/recovery/actions/preview",
      payload: previewBody
    });

    assert.equal(previewResponse.statusCode, 200);
    const preview = previewResponse.json();
    assert.equal(preview.action_type, "document_reindex");
    assert.equal(preview.recommendation_state, "available");
    assert.equal(preview.source_facts.facts.index_status, "failed");
    assert.equal(preview.source_facts.facts.diagnosis_code, "index_embedding_failed");
    assert.equal(
      preview.preconditions.some((precondition: { code: string; satisfied: boolean }) => precondition.code === "document_needs_reindex" && precondition.satisfied),
      true
    );

    const repeatPreviewResponse = await app.inject({
      method: "POST",
      url: "/api/v1/ops/recovery/actions/preview",
      payload: previewBody
    });
    assert.equal(repeatPreviewResponse.statusCode, 200);
    const repeatPreview = repeatPreviewResponse.json();
    assert.equal(repeatPreview.preview_id, preview.preview_id);
    assert.equal(repeatPreview.idempotency_key, preview.idempotency_key);

    const invalidPreviewResponse = await app.inject({
      method: "POST",
      url: "/api/v1/ops/recovery/actions/preview",
      payload: {
        candidate_id: answerCandidate.candidate_id,
        action_type: "answer_diagnostic_rerun",
        target_type: "document",
        target_refs: [{ type: "document", id: seeded.reindexDocumentId }]
      }
    });
    assert.equal(invalidPreviewResponse.statusCode, 400);

    const rollbackResponse = await app.inject({
      method: "GET",
      url: `/api/v1/ops/recovery/rollback-plan?deployment_record_id=${seeded.deploymentRecordId}`
    });

    assert.equal(rollbackResponse.statusCode, 200);
    const rollbackPlan = rollbackResponse.json();
    assert.equal(rollbackPlan.deployment_record_id, seeded.deploymentRecordId);
    assert.equal(rollbackPlan.affected_samples.length, 3);
    assert.equal(rollbackPlan.quality_delta_summary.new_regression_count, 3);
    assert.equal(rollbackPlan.manual_checklist.length > 0, true);
  } finally {
    await app.close();
  }
});

test("phase 3B recovery actions reconcile document reindex execution and audit", async () => {
  await resetDatabase();
  const seeded = await seedRecoveryData();
  const app = await createApp();
  await app.init();

  try {
    const queueService = app.get(QueueService) as {
      enqueueChunkDocument: (documentId: string, jobId: string) => Promise<string>;
    };
    queueService.enqueueChunkDocument = async () => "queue-job-chunk-1";

    const candidatesResponse = await app.inject({
      method: "GET",
      url: `/api/v1/ops/recovery/candidates?source_type=deployment_compare&source_ref=${seeded.deploymentRecordId}&page_size=10`
    });
    assert.equal(candidatesResponse.statusCode, 200);
    const candidates = candidatesResponse.json();
    const reindexCandidate = candidates.items.find(
      (item: { action_type: string; target_refs: Array<{ id: string }> }) =>
        item.action_type === "document_reindex" && item.target_refs[0].id === seeded.reindexDocumentId
    );
    assert.ok(reindexCandidate);

    const previewResponse = await app.inject({
      method: "POST",
      url: "/api/v1/ops/recovery/actions/preview",
      payload: {
        candidate_id: reindexCandidate.candidate_id,
        action_type: "document_reindex",
        target_type: "document",
        target_refs: [{ type: "document", id: seeded.reindexDocumentId }]
      }
    });
    assert.equal(previewResponse.statusCode, 200);
    const preview = previewResponse.json();

    const createResponse = await app.inject({
      method: "POST",
      url: "/api/v1/ops/recovery/actions",
      payload: {
        candidate_id: reindexCandidate.candidate_id,
        preview_id: preview.preview_id,
        idempotency_key: preview.idempotency_key,
        reason: "Operator approved reindex after diagnostics review."
      }
    });

    assert.equal(createResponse.statusCode, 200);
    const action = createResponse.json();
    assert.equal(action.status, "queued");
    assert.equal(action.queue_job_refs.length, 1);
    const rootJobId = getJobIdFromRef(action.queue_job_refs[0]);
    assert.ok(rootJobId);

    await markRecoveryActionRunning(rootJobId);

    const runningResponse = await app.inject({
      method: "GET",
      url: `/api/v1/ops/recovery/actions/${action.action_id}`
    });
    assert.equal(runningResponse.statusCode, 200);
    const runningAction = runningResponse.json();
    assert.equal(runningAction.status, "running");
    assert.ok(runningAction.started_at);

    const embedJobId = await markReindexRecoverySucceeded(seeded.reindexDocumentId, rootJobId);
    assert.ok(embedJobId);

    const succeededResponse = await app.inject({
      method: "GET",
      url: `/api/v1/ops/recovery/actions/${action.action_id}`
    });
    assert.equal(succeededResponse.statusCode, 200);
    const succeededAction = succeededResponse.json();
    assert.equal(succeededAction.status, "succeeded");
    assert.equal(succeededAction.queue_job_refs.length, 2);
    assert.ok(succeededAction.completed_at);

    const auditResponse = await app.inject({
      method: "GET",
      url: `/api/v1/ops/recovery/actions/${action.action_id}/audit`
    });
    assert.equal(auditResponse.statusCode, 200);
    const audit = auditResponse.json();
    assert.equal(audit.action.status, "succeeded");
    assert.equal(audit.before_facts.facts.index_status, "failed");
    assert.equal(audit.after_facts.facts.index_status, "ready");
    assert.equal(audit.status_timeline.some((entry: { status: string }) => entry.status === "queued"), true);
    assert.equal(audit.status_timeline.some((entry: { status: string }) => entry.status === "running"), true);
    assert.equal(audit.status_timeline.some((entry: { status: string }) => entry.status === "succeeded"), true);
    assert.equal(audit.manual_follow_up[0].includes(`/api/v1/ops/replays/documents/${seeded.reindexDocumentId}`), true);
  } finally {
    await app.close();
  }
});

test("phase 3B answer diagnostic rerun stays blocked and idempotent", async () => {
  await resetDatabase();
  const seeded = await seedRecoveryData();
  const app = await createApp();
  await app.init();

  try {
    const candidateId = `answer_session_replay:answer_diagnostic_rerun:answer_session:${seeded.answerSessionId}`;

    const previewResponse = await app.inject({
      method: "POST",
      url: "/api/v1/ops/recovery/actions/preview",
      payload: {
        candidate_id: candidateId,
        action_type: "answer_diagnostic_rerun",
        target_type: "answer_session",
        target_refs: [{ type: "answer_session", id: seeded.answerSessionId }]
      }
    });
    assert.equal(previewResponse.statusCode, 200);
    const preview = previewResponse.json();

    const createPayload = {
      candidate_id: candidateId,
      preview_id: preview.preview_id,
      idempotency_key: preview.idempotency_key,
      reason: "Document the diagnostic rerun request for manual follow-up."
    };

    const createResponse = await app.inject({
      method: "POST",
      url: "/api/v1/ops/recovery/actions",
      payload: createPayload
    });
    assert.equal(createResponse.statusCode, 200);
    const blockedAction = createResponse.json();
    assert.equal(blockedAction.status, "blocked");
    assert.equal(blockedAction.queue_job_refs.length, 0);

    const duplicateResponse = await app.inject({
      method: "POST",
      url: "/api/v1/ops/recovery/actions",
      payload: createPayload
    });
    assert.equal(duplicateResponse.statusCode, 200);
    const duplicateAction = duplicateResponse.json();
    assert.equal(duplicateAction.action_id, blockedAction.action_id);
    assert.equal(duplicateAction.status, "blocked");

    const auditResponse = await app.inject({
      method: "GET",
      url: `/api/v1/ops/recovery/actions/${blockedAction.action_id}/audit`
    });
    assert.equal(auditResponse.statusCode, 200);
    const audit = auditResponse.json();
    assert.equal(audit.actor, "ops-operator");
    assert.equal(audit.manual_follow_up[0].includes(`/api/v1/ops/replays/answer-sessions/${seeded.answerSessionId}`), true);
    assert.equal(audit.status_timeline.length, 1);
    assert.equal(audit.status_timeline[0].status, "blocked");

    const notFoundResponse = await app.inject({
      method: "GET",
      url: `/api/v1/ops/recovery/actions/${randomUUID()}`
    });
    assert.equal(notFoundResponse.statusCode, 404);
  } finally {
    await app.close();
  }
});
