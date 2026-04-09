import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import test from "node:test";
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
      "truncate table answer_citations, retrieval_run_hits, retrieval_runs, answer_sessions, document_chunks, document_processing_events, document_parse_jobs, document_tags, uploads, tags, documents restart identity cascade"
    );
  } finally {
    await pool.end();
  }
}

async function insertReadyDocumentWithChunk() {
  const pool = new Pool({
    connectionString: databaseUrl
  });
  const documentId = randomUUID();
  const chunkId = randomUUID();
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
          index_version,
          indexed_at,
          citation_ready,
          content_preview,
          search_text,
          created_at,
          imported_at,
          updated_at
        )
        values ($1, 'Answer contract document', 'text', 'manual_input', 'success', 'ready', 'phase-2a-test', $2, true, 'Answer contract document preview', 'answer contract document', $3, $3, $3)
      `,
      [documentId, now, now]
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
        values ($1, $2, 0, 'phase-2a-test', '回答依据', 'p. 3', '可引用的答案证据。', 9, $3, $4, $5)
      `,
      [chunkId, documentId, "e".repeat(64), { page: 3, paragraph: 2 }, now]
    );
  } finally {
    await pool.end();
  }

  return { documentId, chunkId };
}

async function seedAnsweredSession(sessionId: string, documentId: string, chunkId: string) {
  const pool = new Pool({
    connectionString: databaseUrl
  });
  const runId = randomUUID();
  const now = new Date();

  try {
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
        values ($1, $2, 'what evidence is available', 1, 1, 1, 1, 'hybrid', 55, $3)
      `,
      [runId, sessionId, now]
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
        values ($1, $2, $3, $4, 1, 0.9000, 0.8000, 0.9500, true, null, $5)
      `,
      [randomUUID(), runId, documentId, chunkId, now]
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
        values ($1, $2, $3, $4, 'claim-1', 'Grounded evidence quote', $5, $6)
      `,
      [randomUUID(), sessionId, documentId, chunkId, { page: 3, paragraph: 2 }, now]
    );
  } finally {
    await pool.end();
  }

  return sessionId;
}

test("answers API creates and exposes answer session, citations, and retrieval trace", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const queueService = app.get(QueueService) as { enqueueAnswerSession: (sessionId: string) => Promise<string> };
    queueService.enqueueAnswerSession = async () => "queue-job-answer-1";

    const { documentId, chunkId } = await insertReadyDocumentWithChunk();

    const createResponse = await app.inject({
      method: "POST",
      url: "/api/v1/answers",
      payload: {
        question: "  What evidence is available?  ",
        scope: {
          mode: "search_result",
          payload: {
            document_ids: [documentId],
            truncated: false
          }
        }
      }
    });

    assert.equal(createResponse.statusCode, 202);
    const created = createResponse.json();
    assert.equal(created.status, "retrieving");
    assert.ok(created.session_id);

    const sessionId = created.session_id as string;

    const sessionRowResponse = await app.inject({
      method: "GET",
      url: `/api/v1/answers/${sessionId}`
    });
    assert.equal(sessionRowResponse.statusCode, 200);
    const initialSession = sessionRowResponse.json();
    assert.equal(initialSession.question, "What evidence is available?");
    assert.equal(initialSession.status, "retrieving");
    assert.equal(initialSession.scope.mode, "search_result");
    assert.deepEqual(initialSession.scope.payload.document_ids, [documentId]);

    await seedAnsweredSession(sessionId, documentId, chunkId);

    const pool = new Pool({
      connectionString: databaseUrl
    });
    try {
      await pool.query(
        `
          update answer_sessions
          set status = 'answered',
              answer_summary = 'The answer is grounded.',
              refusal_reason = null,
              diagnosis_code = null,
              latency_ms = 123,
              prompt_tokens = 14,
              completion_tokens = 28,
              total_cost_usd = 1.2345,
              finished_at = now(),
              updated_at = now()
          where id = $1
        `,
        [sessionId]
      );
    } finally {
      await pool.end();
    }

    const answeredResponse = await app.inject({
      method: "GET",
      url: `/api/v1/answers/${sessionId}`
    });
    assert.equal(answeredResponse.statusCode, 200);
    const answered = answeredResponse.json();
    assert.equal(answered.status, "answered");
    assert.equal(answered.answer_summary, "The answer is grounded.");
    assert.equal(answered.refusal_reason, null);
    assert.equal(answered.diagnosis_code, null);
    assert.equal(answered.retrieval_mode, "hybrid");
    assert.equal(answered.citations.length, 1);
    assert.equal(answered.citations[0].document_id, documentId);
    assert.equal(answered.citations[0].chunk_id, chunkId);
    assert.equal(answered.citations[0].quote_text, "Grounded evidence quote");
    assert.deepEqual(answered.citations[0].locator, { page: 3, paragraph: 2 });
    assert.equal(answered.latency_ms, 123);
    assert.equal(answered.total_cost_usd, "1.2345");

    const retrievalResponse = await app.inject({
      method: "GET",
      url: `/api/v1/answers/${sessionId}/retrieval`
    });
    assert.equal(retrievalResponse.statusCode, 200);
    const retrieval = retrievalResponse.json();
    assert.equal(retrieval.session_id, sessionId);
    assert.equal(retrieval.items.length, 1);
    assert.equal(retrieval.items[0].document_id, documentId);
    assert.equal(retrieval.items[0].chunk_id, chunkId);
    assert.equal(retrieval.items[0].rank, 1);
    assert.equal(retrieval.items[0].used_in_answer, true);
    assert.equal(retrieval.items[0].final_score, 0.95);
  } finally {
    await app.close();
  }
});
