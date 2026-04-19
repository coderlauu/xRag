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
      "truncate table answer_claims, answer_citations, retrieval_run_hits, retrieval_runs, answer_sessions, document_chunks, document_processing_events, document_parse_jobs, document_tags, uploads, tags, documents restart identity cascade"
    );
  } finally {
    await pool.end();
  }
}

type SeedEvidenceClaim = {
  claimSlot: string;
  claimText: string;
  freshnessBadge: "ready" | "stale_risk" | "unknown";
  citations: Array<{
    documentId: string;
    chunkId: string;
    quoteText: string;
    locator: Record<string, unknown> | null;
  }>;
};

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

async function seedAnsweredSession(
  sessionId: string,
  documentId: string,
  chunkId: string,
  claims: SeedEvidenceClaim[] = [
    {
      claimSlot: "claim-1",
      claimText: "The answer is grounded.",
      freshnessBadge: "ready",
      citations: [
        {
          documentId,
          chunkId,
          quoteText: "Grounded evidence quote",
          locator: { page: 3, paragraph: 2 }
        }
      ]
    }
  ]
) {
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

    for (const [index, claim] of claims.entries()) {
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
          values ($1, $2, $3, $4, $5, $6, $7)
        `,
        [randomUUID(), sessionId, claim.claimSlot, index + 1, claim.claimText, claim.freshnessBadge, now]
      );

      for (const citation of claim.citations) {
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
            values ($1, $2, $3, $4, $5, $6, $7, $8)
          `,
          [
            randomUUID(),
            sessionId,
            citation.documentId,
            citation.chunkId,
            claim.claimSlot,
            citation.quoteText,
            citation.locator,
            now
          ]
        );
      }
    }
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
      url: "/api/v1/answers"
    });
    assert.equal(sessionRowResponse.statusCode, 200);
    const sessionList = sessionRowResponse.json();
    assert.equal(sessionList.items.length, 1);
    assert.equal(sessionList.items[0].session_id, sessionId);
    assert.equal(sessionList.items[0].continued_from_session_id, null);
    assert.equal(sessionList.items[0].scope_summary, `搜索结果 · 1 个文档`);

    const initialSessionResponse = await app.inject({
      method: "GET",
      url: `/api/v1/answers/${sessionId}`
    });
    assert.equal(initialSessionResponse.statusCode, 200);
    const initialSession = initialSessionResponse.json();
    assert.equal(initialSession.question, "What evidence is available?");
    assert.equal(initialSession.status, "retrieving");
    assert.equal(initialSession.scope.mode, "search_result");
    assert.deepEqual(initialSession.scope.payload.document_ids, [documentId]);
    assert.equal(initialSession.scope_summary, "搜索结果 · 1 个文档");
    assert.equal(initialSession.continued_from_session_id, null);
    assert.deepEqual(initialSession.evidence_groups, []);

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
              updated_at = now(),
              finished_at = now()
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
    assert.equal(answered.evidence_groups.length, 1);
    assert.equal(answered.evidence_groups[0].claim_slot, "claim-1");
    assert.equal(answered.evidence_groups[0].freshness_badge, "ready");
    assert.equal(answered.evidence_groups[0].citations.length, 1);
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
    assert.equal(retrieval.summary.query_normalized, "what evidence is available");
    assert.equal(retrieval.summary.rerank_strategy, "hybrid");
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

test("answers API reconciles stale active sessions to failed on read", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();
  const sessionId = randomUUID();
  const staleUpdatedAt = new Date(Date.now() - 11 * 60 * 1000);

  try {
    const pool = new Pool({
      connectionString: databaseUrl
    });
    try {
      await pool.query(
        `
          insert into answer_sessions (
            id,
            queue_job_id,
            question,
            scope_mode,
            scope_payload,
            retrieval_mode,
            status,
            created_at,
            updated_at
          )
          values ($1, 'queue-job-stale-active', 'Will this stop polling?', 'global', null, 'hybrid', 'retrieving', $2, $2)
        `,
        [sessionId, staleUpdatedAt]
      );
    } finally {
      await pool.end();
    }

    const response = await app.inject({
      method: "GET",
      url: `/api/v1/answers/${sessionId}`
    });
    assert.equal(response.statusCode, 200);
    const body = response.json();
    assert.equal(body.status, "failed");
    assert.equal(body.diagnosis_code, "queue_backlog");
    assert.match(body.refusal_reason, /exceeded active processing timeout/);

    const poolAfter = new Pool({
      connectionString: databaseUrl
    });
    try {
      const result = await poolAfter.query<{ status: string; finished_at: Date | null }>(
        "select status, finished_at from answer_sessions where id = $1",
        [sessionId]
      );
      assert.equal(result.rows[0]?.status, "failed");
      assert.ok(result.rows[0]?.finished_at);
    } finally {
      await poolAfter.end();
    }
  } finally {
    await app.close();
  }
});

test("answers API orders recent history by created_at and exposes continue lineage", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const queueService = app.get(QueueService) as { enqueueAnswerSession: (sessionId: string) => Promise<string> };
    queueService.enqueueAnswerSession = async () => "queue-job-answer-history";

    const { documentId } = await insertReadyDocumentWithChunk();

    const initialCreate = await app.inject({
      method: "POST",
      url: "/api/v1/answers",
      payload: {
        question: "Initial question",
        scope: {
          mode: "global",
          payload: {
            filters: {
              tags: ["weekly"],
              source_types: ["pdf"],
              date_from: "2026-01-01T00:00:00.000Z",
              date_to: "2026-04-12T00:00:00.000Z"
            }
          }
        }
      }
    });
    assert.equal(initialCreate.statusCode, 202);
    const initialSessionId = initialCreate.json().session_id as string;

    const followupCreate = await app.inject({
      method: "POST",
      url: "/api/v1/answers",
      payload: {
        question: "Follow-up question",
        continued_from_session_id: initialSessionId,
        scope: {
          mode: "search_result",
          payload: {
            document_ids: [documentId],
            truncated: false,
            filters: {
              tags: ["weekly"],
              source_types: ["pdf"]
            }
          }
        }
      }
    });
    assert.equal(followupCreate.statusCode, 202);
    const followupSessionId = followupCreate.json().session_id as string;

    const pool = new Pool({
      connectionString: databaseUrl
    });
    try {
      await pool.query(
        `
          update answer_sessions
          set created_at = $2,
              updated_at = $3
          where id = $1
        `,
        [initialSessionId, new Date("2026-04-13T10:00:00.000Z"), new Date("2026-04-13T12:00:00.000Z")]
      );
      await pool.query(
        `
          update answer_sessions
          set created_at = $2,
              updated_at = $3
          where id = $1
        `,
        [followupSessionId, new Date("2026-04-13T11:00:00.000Z"), new Date("2026-04-13T11:00:01.000Z")]
      );
    } finally {
      await pool.end();
    }

    const listResponse = await app.inject({
      method: "GET",
      url: "/api/v1/answers"
    });
    assert.equal(listResponse.statusCode, 200);
    const list = listResponse.json();
    assert.equal(list.items.length, 2);
    assert.equal(list.items[0].session_id, followupSessionId);
    assert.equal(list.items[0].continued_from_session_id, initialSessionId);
    assert.equal(list.items[0].scope_summary, "搜索结果 · 1 个文档；标签 weekly；来源 pdf");
    assert.equal(list.items[1].session_id, initialSessionId);
    assert.equal(
      list.items[1].scope_summary,
      "全库；标签 weekly；来源 pdf；时间 2026-01-01T00:00:00.000Z 至 2026-04-12T00:00:00.000Z"
    );

    const followupDetailResponse = await app.inject({
      method: "GET",
      url: `/api/v1/answers/${followupSessionId}`
    });
    assert.equal(followupDetailResponse.statusCode, 200);
    const followupDetail = followupDetailResponse.json();
    assert.equal(followupDetail.continued_from_session_id, initialSessionId);
    assert.equal(followupDetail.scope_summary, "搜索结果 · 1 个文档；标签 weekly；来源 pdf");
  } finally {
    await app.close();
  }
});

test("answers API rejects unknown continued_from_session_id", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const response = await app.inject({
      method: "POST",
      url: "/api/v1/answers",
      payload: {
        question: "Follow-up question",
        continued_from_session_id: randomUUID(),
        scope: {
          mode: "global",
          payload: null
        }
      }
    });

    assert.equal(response.statusCode, 404);
    assert.equal(response.json().message, "Continued answer session not found");
  } finally {
    await app.close();
  }
});

test("answers API preserves search_result snapshot query and filters for recent history rehydration", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const queueService = app.get(QueueService) as { enqueueAnswerSession: (sessionId: string) => Promise<string> };
    queueService.enqueueAnswerSession = async () => "queue-job-answer-snapshot";

    const { documentId } = await insertReadyDocumentWithChunk();

    const initialCreate = await app.inject({
      method: "POST",
      url: "/api/v1/answers",
      payload: {
        question: "Initial snapshot question",
        scope: {
          mode: "global",
          payload: null
        }
      }
    });
    assert.equal(initialCreate.statusCode, 202);
    const initialSessionId = initialCreate.json().session_id as string;

    const createResponse = await app.inject({
      method: "POST",
      url: "/api/v1/answers",
      payload: {
        question: "Scoped snapshot question",
        continued_from_session_id: initialSessionId,
        scope: {
          mode: "search_result",
          payload: {
            document_ids: [documentId],
            truncated: false,
            query: "phase 2b query snapshot",
            filters: {
              tags: ["phase-2b", "history"],
              source_types: ["text"],
              date_from: "2026-04-01T00:00:00.000Z",
              date_to: "2026-04-14T00:00:00.000Z"
            }
          }
        }
      }
    });
    assert.equal(createResponse.statusCode, 202);
    const sessionId = createResponse.json().session_id as string;

    const detailResponse = await app.inject({
      method: "GET",
      url: `/api/v1/answers/${sessionId}`
    });
    assert.equal(detailResponse.statusCode, 200);
    const detail = detailResponse.json();
    assert.equal(detail.continued_from_session_id, initialSessionId);
    assert.equal(detail.scope.mode, "search_result");
    assert.deepEqual(detail.scope.payload.document_ids, [documentId]);
    assert.equal(detail.scope.payload.query, "phase 2b query snapshot");
    assert.deepEqual(detail.scope.payload.filters, {
      tags: ["phase-2b", "history"],
      source_types: ["text"],
      date_from: "2026-04-01T00:00:00.000Z",
      date_to: "2026-04-14T00:00:00.000Z"
    });

    const listResponse = await app.inject({
      method: "GET",
      url: "/api/v1/answers?page=1&page_size=1"
    });
    assert.equal(listResponse.statusCode, 200);
    const list = listResponse.json();
    assert.equal(list.page, 1);
    assert.equal(list.page_size, 1);
    assert.equal(list.total, 2);
    assert.equal(list.items.length, 1);
    assert.equal(list.items[0].session_id, sessionId);
    assert.equal(list.items[0].continued_from_session_id, initialSessionId);
    assert.equal(list.items[0].scope.mode, "search_result");
    assert.deepEqual(list.items[0].scope.payload.document_ids, [documentId]);
    assert.equal(list.items[0].scope.payload.query, "phase 2b query snapshot");
    assert.deepEqual(list.items[0].scope.payload.filters, {
      tags: ["phase-2b", "history"],
      source_types: ["text"],
      date_from: "2026-04-01T00:00:00.000Z",
      date_to: "2026-04-14T00:00:00.000Z"
    });
  } finally {
    await app.close();
  }
});

test("answers API keeps evidence groups scoped when multiple claims cite the same chunk", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const queueService = app.get(QueueService) as { enqueueAnswerSession: (sessionId: string) => Promise<string> };
    queueService.enqueueAnswerSession = async () => "queue-job-answer-groups";

    const { documentId, chunkId } = await insertReadyDocumentWithChunk();
    const createResponse = await app.inject({
      method: "POST",
      url: "/api/v1/answers",
      payload: {
        question: "What evidence is available?",
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
    const sessionId = createResponse.json().session_id as string;

    await seedAnsweredSession(sessionId, documentId, chunkId, [
      {
        claimSlot: "claim-1",
        claimText: "Claim one.",
        freshnessBadge: "ready",
        citations: [
          {
            documentId,
            chunkId,
            quoteText: "Grounded evidence quote A",
            locator: { page: 3, paragraph: 2 }
          }
        ]
      },
      {
        claimSlot: "claim-2",
        claimText: "Claim two.",
        freshnessBadge: "ready",
        citations: [
          {
            documentId,
            chunkId,
            quoteText: "Grounded evidence quote B",
            locator: { page: 3, paragraph: 3 }
          }
        ]
      }
    ]);

    const pool = new Pool({
      connectionString: databaseUrl
    });
    try {
      await pool.query(
        `
          update answer_sessions
          set status = 'answered',
              answer_summary = 'Two grounded claims.',
              updated_at = now(),
              finished_at = now()
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
    assert.equal(answered.citations.length, 2);
    assert.equal(answered.evidence_groups.length, 2);
    assert.equal(answered.evidence_groups[0].claim_slot, "claim-1");
    assert.equal(answered.evidence_groups[0].citations.length, 1);
    assert.equal(answered.evidence_groups[0].citations[0].quote_text, "Grounded evidence quote A");
    assert.equal(answered.evidence_groups[1].claim_slot, "claim-2");
    assert.equal(answered.evidence_groups[1].citations.length, 1);
    assert.equal(answered.evidence_groups[1].citations[0].quote_text, "Grounded evidence quote B");
  } finally {
    await app.close();
  }
});

test("API rejects invalid uuid path params with 400 instead of 500", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const cases: Array<{ url: string; method: "GET" | "POST" | "PATCH" }> = [
      { url: "/api/v1/answers/not-a-uuid", method: "GET" },
      { url: "/api/v1/answers/not-a-uuid/retrieval", method: "GET" },
      { url: "/api/v1/documents/not-a-uuid", method: "GET" },
      { url: "/api/v1/documents/not-a-uuid/evidence", method: "GET" },
      { url: "/api/v1/documents/not-a-uuid/timeline", method: "GET" },
      { url: "/api/v1/jobs/not-a-uuid", method: "GET" },
      { url: "/api/v1/ops/replays/answer-sessions/not-a-uuid", method: "GET" },
      { url: "/api/v1/ops/replays/documents/not-a-uuid", method: "GET" },
      { url: "/api/v1/ops/recovery/actions/not-a-uuid", method: "GET" },
      { url: "/api/v1/ops/recovery/actions/not-a-uuid/audit", method: "GET" }
    ];

    for (const { url, method } of cases) {
      const response = await app.inject({ method, url });
      assert.equal(
        response.statusCode,
        400,
        `expected 400 for ${method} ${url}, got ${response.statusCode}: ${response.payload}`
      );
      const payload = response.json() as { message?: unknown; error?: string };
      assert.equal(payload.error, "Bad Request", `error label mismatch for ${method} ${url}`);
    }
  } finally {
    await app.close();
  }
});
