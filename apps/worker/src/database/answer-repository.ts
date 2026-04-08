import { randomUUID } from "node:crypto";
import type { Pool, PoolClient } from "pg";
import { normalizeWhitespace } from "../common/document-utils";
import type { AnswerSessionStatus } from "@xrag/shared-types";

type DatabaseExecutor = Pick<Pool | PoolClient, "query">;

export interface AnswerSessionRecord {
  id: string;
  ownerId: string | null;
  queueJobId: string | null;
  question: string;
  scopeMode: "global" | "search_result" | "document";
  scopePayload: Record<string, unknown> | null;
  retrievalMode: "hybrid";
  status: AnswerSessionStatus;
  answerSummary: string | null;
  refusalReason: string | null;
  diagnosisCode: string | null;
  providerName: string | null;
  providerModel: string | null;
  latencyMs: number | null;
  promptTokens: number | null;
  completionTokens: number | null;
  totalCostUsd: string | null;
  createdAt: Date;
  finishedAt: Date | null;
}

export interface AnswerChunkRecord {
  id: string;
  documentId: string;
  documentTitle: string;
  chunkIndex: number;
  sectionLabel: string | null;
  pageRef: string | null;
  contentText: string;
  contentSha256: string;
  citationLocator: Record<string, unknown> | null;
}

export interface RetrievalChunkCandidateRecord extends AnswerChunkRecord {
  lexicalScore: number | null;
  semanticScore: number | null;
  finalScore: number | null;
}

export interface RetrievalRunRecord {
  id: string;
  sessionId: string;
  queryNormalized: string;
  eligibleDocumentCount: number;
  lexicalHitCount: number;
  semanticHitCount: number;
  mergedHitCount: number;
  rerankStrategy: string;
  latencyMs: number | null;
  createdAt: Date;
}

export interface RetrievalTraceHitRecord {
  retrievalRunId: string;
  documentId: string;
  chunkId: string | null;
  rank: number;
  lexicalScore: number | null;
  semanticScore: number | null;
  finalScore: number | null;
  usedInAnswer: boolean;
  exclusionReason: string | null;
}

export interface AnswerCitationInsertRecord {
  sessionId: string;
  documentId: string;
  chunkId: string;
  claimSlot: string;
  quoteText: string;
  locator: Record<string, unknown> | null;
}

export interface AnswerSessionUpdate {
  queueJobId?: string | null;
  status?: AnswerSessionStatus;
  answerSummary?: string | null;
  refusalReason?: string | null;
  diagnosisCode?: string | null;
  providerName?: string | null;
  providerModel?: string | null;
  latencyMs?: number | null;
  promptTokens?: number | null;
  completionTokens?: number | null;
  totalCostUsd?: string | number | null;
  finishedAt?: boolean;
}

export class AnswerRepository {
  constructor(private readonly pool: Pool) {}

  async withTransaction<T>(callback: (db: DatabaseExecutor) => Promise<T>): Promise<T> {
    const client = await this.pool.connect();
    try {
      await client.query("begin");
      const result = await callback(client);
      await client.query("commit");
      return result;
    } catch (error) {
      await client.query("rollback");
      throw error;
    } finally {
      client.release();
    }
  }

  async getAnswerSessionById(sessionId: string, db: DatabaseExecutor = this.pool): Promise<AnswerSessionRecord | null> {
    const result = await db.query<AnswerSessionRecord>(
      `
        select
          id,
          owner_id as "ownerId",
          queue_job_id as "queueJobId",
          question,
          scope_mode as "scopeMode",
          scope_payload as "scopePayload",
          retrieval_mode as "retrievalMode",
          status,
          answer_summary as "answerSummary",
          refusal_reason as "refusalReason",
          diagnosis_code as "diagnosisCode",
          provider_name as "providerName",
          provider_model as "providerModel",
          latency_ms as "latencyMs",
          prompt_tokens as "promptTokens",
          completion_tokens as "completionTokens",
          total_cost_usd as "totalCostUsd",
          created_at as "createdAt",
          finished_at as "finishedAt"
        from answer_sessions
        where id = $1
        limit 1
      `,
      [sessionId]
    );

    return result.rowCount ? result.rows[0] : null;
  }

  async updateAnswerSession(sessionId: string, values: AnswerSessionUpdate, db: DatabaseExecutor = this.pool): Promise<void> {
    const assignments: string[] = [];
    const params: unknown[] = [sessionId];

    const push = (column: string, value: unknown) => {
      params.push(value);
      assignments.push(`${column} = $${params.length}`);
    };

    if (values.queueJobId !== undefined) {
      push("queue_job_id", values.queueJobId);
    }
    if (values.status !== undefined) {
      push("status", values.status);
    }
    if (values.answerSummary !== undefined) {
      push("answer_summary", values.answerSummary);
    }
    if (values.refusalReason !== undefined) {
      push("refusal_reason", values.refusalReason);
    }
    if (values.diagnosisCode !== undefined) {
      push("diagnosis_code", values.diagnosisCode);
    }
    if (values.providerName !== undefined) {
      push("provider_name", values.providerName);
    }
    if (values.providerModel !== undefined) {
      push("provider_model", values.providerModel);
    }
    if (values.latencyMs !== undefined) {
      push("latency_ms", values.latencyMs);
    }
    if (values.promptTokens !== undefined) {
      push("prompt_tokens", values.promptTokens);
    }
    if (values.completionTokens !== undefined) {
      push("completion_tokens", values.completionTokens);
    }
    if (values.totalCostUsd !== undefined) {
      push("total_cost_usd", normalizeNumericText(values.totalCostUsd));
    }
    if (values.finishedAt) {
      assignments.push("finished_at = now()");
    }

    if (assignments.length === 0) {
      return;
    }

    await db.query(
      `
        update answer_sessions
        set ${assignments.join(", ")}
        where id = $1
      `,
      params
    );
  }

  async markAnswerSessionRetrieving(sessionId: string, queueJobId: string | null, db: DatabaseExecutor = this.pool) {
    await this.updateAnswerSession(
      sessionId,
      {
        queueJobId,
        status: "retrieving",
        refusalReason: null,
        diagnosisCode: null
      },
      db
    );
  }

  async markAnswerSessionSynthesizing(sessionId: string, db: DatabaseExecutor = this.pool) {
    await this.updateAnswerSession(sessionId, { status: "synthesizing" }, db);
  }

  async markAnswerSessionAnswered(
    sessionId: string,
    values: {
      answerSummary: string;
      providerName: string | null;
      providerModel: string | null;
      latencyMs: number | null;
      promptTokens: number | null;
      completionTokens: number | null;
      totalCostUsd: string | number | null;
    },
    db: DatabaseExecutor = this.pool
  ) {
    await this.updateAnswerSession(
      sessionId,
      {
        status: "answered",
        answerSummary: values.answerSummary,
        refusalReason: null,
        diagnosisCode: null,
        providerName: values.providerName,
        providerModel: values.providerModel,
        latencyMs: values.latencyMs,
        promptTokens: values.promptTokens,
        completionTokens: values.completionTokens,
        totalCostUsd: values.totalCostUsd,
        finishedAt: true
      },
      db
    );
  }

  async markAnswerSessionNeedsScope(
    sessionId: string,
    values: {
      refusalReason: string;
      diagnosisCode: string | null;
      providerName: string | null;
      providerModel: string | null;
      latencyMs: number | null;
      promptTokens: number | null;
      completionTokens: number | null;
      totalCostUsd: string | number | null;
    },
    db: DatabaseExecutor = this.pool
  ) {
    await this.updateAnswerSession(
      sessionId,
      {
        status: "needs_scope",
        answerSummary: null,
        refusalReason: values.refusalReason,
        diagnosisCode: values.diagnosisCode,
        providerName: values.providerName,
        providerModel: values.providerModel,
        latencyMs: values.latencyMs,
        promptTokens: values.promptTokens,
        completionTokens: values.completionTokens,
        totalCostUsd: values.totalCostUsd,
        finishedAt: true
      },
      db
    );
  }

  async markAnswerSessionRefused(
    sessionId: string,
    values: {
      refusalReason: string;
      diagnosisCode: string | null;
      providerName: string | null;
      providerModel: string | null;
      latencyMs: number | null;
      promptTokens: number | null;
      completionTokens: number | null;
      totalCostUsd: string | number | null;
    },
    db: DatabaseExecutor = this.pool
  ) {
    await this.updateAnswerSession(
      sessionId,
      {
        status: "refused",
        answerSummary: null,
        refusalReason: values.refusalReason,
        diagnosisCode: values.diagnosisCode,
        providerName: values.providerName,
        providerModel: values.providerModel,
        latencyMs: values.latencyMs,
        promptTokens: values.promptTokens,
        completionTokens: values.completionTokens,
        totalCostUsd: values.totalCostUsd,
        finishedAt: true
      },
      db
    );
  }

  async markAnswerSessionFailed(
    sessionId: string,
    values: {
      refusalReason: string | null;
      diagnosisCode: string | null;
      providerName: string | null;
      providerModel: string | null;
      latencyMs: number | null;
      promptTokens: number | null;
      completionTokens: number | null;
      totalCostUsd: string | number | null;
    },
    db: DatabaseExecutor = this.pool
  ) {
    await this.updateAnswerSession(
      sessionId,
      {
        status: "failed",
        answerSummary: null,
        refusalReason: values.refusalReason,
        diagnosisCode: values.diagnosisCode,
        providerName: values.providerName,
        providerModel: values.providerModel,
        latencyMs: values.latencyMs,
        promptTokens: values.promptTokens,
        completionTokens: values.completionTokens,
        totalCostUsd: values.totalCostUsd,
        finishedAt: true
      },
      db
    );
  }

  async countEligibleDocuments(documentIds: string[] | null, db: DatabaseExecutor = this.pool): Promise<number> {
    if (documentIds && documentIds.length === 0) {
      return 0;
    }

    const params: unknown[] = [];
    const where: string[] = [
      "d.index_status = 'ready'",
      "d.citation_ready = true",
      "c.embedding is not null"
    ];

    if (documentIds && documentIds.length > 0) {
      params.push(documentIds);
      where.push(`d.id = any($${params.length}::uuid[])`);
    }

    const result = await db.query<{ count: string }>(
      `
        select count(distinct d.id)::text as count
        from documents d
        inner join document_chunks c on c.document_id = d.id
        where ${where.join(" and ")}
      `,
      params
    );

    return Number(result.rows[0]?.count ?? 0);
  }

  async listLexicalChunkCandidates(
    question: string,
    documentIds: string[] | null,
    limit: number,
    db: DatabaseExecutor = this.pool
  ): Promise<RetrievalChunkCandidateRecord[]> {
    if (limit <= 0) {
      return [];
    }

    const params: unknown[] = [question, limit];
    const where = buildChunkWhereClause(documentIds, params);
    const result = await db.query<RetrievalChunkCandidateRow>(
      `
        select
          c.id as "chunkId",
          c.document_id as "documentId",
          d.title as "documentTitle",
          c.chunk_index as "chunkIndex",
          c.section_label as "sectionLabel",
          c.page_ref as "pageRef",
          c.content_text as "contentText",
          c.content_sha256 as "contentSha256",
          c.citation_locator as "citationLocator",
          ts_rank_cd(to_tsvector('simple', c.content_text), plainto_tsquery('simple', $1)) as score
        from document_chunks c
        inner join documents d on d.id = c.document_id
        where ${where}
          and to_tsvector('simple', c.content_text) @@ plainto_tsquery('simple', $1)
        order by score desc, c.chunk_index asc
        limit $2
      `,
      params
    );

    return result.rows.map((row) => ({
      id: row.chunkId,
      documentId: row.documentId,
      documentTitle: row.documentTitle,
      chunkIndex: row.chunkIndex,
      sectionLabel: row.sectionLabel,
      pageRef: row.pageRef,
      contentText: row.contentText,
      contentSha256: row.contentSha256,
      citationLocator: row.citationLocator,
      lexicalScore: toNumber(row.score),
      semanticScore: null,
      finalScore: null
    }));
  }

  async listSemanticChunkCandidates(
    queryVector: readonly number[],
    documentIds: string[] | null,
    limit: number,
    db: DatabaseExecutor = this.pool
  ): Promise<RetrievalChunkCandidateRecord[]> {
    if (limit <= 0) {
      return [];
    }

    const vectorLiteral = serializeVector(queryVector);
    const params: unknown[] = [vectorLiteral, limit];
    const where = buildChunkWhereClause(documentIds, params);
    const result = await db.query<RetrievalChunkCandidateRow>(
      `
        select
          c.id as "chunkId",
          c.document_id as "documentId",
          d.title as "documentTitle",
          c.chunk_index as "chunkIndex",
          c.section_label as "sectionLabel",
          c.page_ref as "pageRef",
          c.content_text as "contentText",
          c.content_sha256 as "contentSha256",
          c.citation_locator as "citationLocator",
          greatest(0, least(1, 1 - (c.embedding <=> $1::vector))) as score
        from document_chunks c
        inner join documents d on d.id = c.document_id
        where ${where}
          and c.embedding is not null
        order by c.embedding <=> $1::vector asc, c.chunk_index asc
        limit $2
      `,
      params
    );

    return result.rows.map((row) => ({
      id: row.chunkId,
      documentId: row.documentId,
      documentTitle: row.documentTitle,
      chunkIndex: row.chunkIndex,
      sectionLabel: row.sectionLabel,
      pageRef: row.pageRef,
      contentText: row.contentText,
      contentSha256: row.contentSha256,
      citationLocator: row.citationLocator,
      lexicalScore: null,
      semanticScore: toNumber(row.score),
      finalScore: null
    }));
  }

  async createRetrievalRun(
    values: {
      sessionId: string;
      queryNormalized: string;
      eligibleDocumentCount: number;
      lexicalHitCount: number;
      semanticHitCount: number;
      mergedHitCount: number;
      rerankStrategy: string;
      latencyMs: number | null;
    },
    db: DatabaseExecutor = this.pool
  ): Promise<RetrievalRunRecord> {
    const id = randomUUID();
    const result = await db.query<RetrievalRunRow>(
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
          latency_ms
        ) values ($1, $2, $3, $4, $5, $6, $7, $8, $9)
        returning
          id,
          session_id as "sessionId",
          query_normalized as "queryNormalized",
          eligible_document_count as "eligibleDocumentCount",
          lexical_hit_count as "lexicalHitCount",
          semantic_hit_count as "semanticHitCount",
          merged_hit_count as "mergedHitCount",
          rerank_strategy as "rerankStrategy",
          latency_ms as "latencyMs",
          created_at as "createdAt"
      `,
      [
        id,
        values.sessionId,
        values.queryNormalized,
        values.eligibleDocumentCount,
        values.lexicalHitCount,
        values.semanticHitCount,
        values.mergedHitCount,
        values.rerankStrategy,
        values.latencyMs
      ]
    );

    return result.rows[0];
  }

  async insertRetrievalRunHits(
    values: RetrievalTraceHitRecord[],
    db: DatabaseExecutor = this.pool
  ): Promise<void> {
    if (values.length === 0) {
      return;
    }

    const params: unknown[] = [];
    const rows = values
      .map((value) => {
        const base = params.length;
        params.push(
          randomUUID(),
          value.retrievalRunId,
          value.documentId,
          value.chunkId,
          value.rank,
          value.lexicalScore,
          value.semanticScore,
          value.finalScore,
          value.usedInAnswer,
          value.exclusionReason
        );
        return `($${base + 1}, $${base + 2}, $${base + 3}, $${base + 4}, $${base + 5}, $${base + 6}, $${base + 7}, $${base + 8}, $${base + 9}, $${base + 10})`;
      })
      .join(", ");

    await db.query(
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
          exclusion_reason
        ) values ${rows}
      `,
      params
    );
  }

  async markRetrievalHitsUsedInAnswer(
    retrievalRunId: string,
    chunkIds: string[],
    db: DatabaseExecutor = this.pool
  ): Promise<void> {
    if (chunkIds.length === 0) {
      return;
    }

    await db.query(
      `
        update retrieval_run_hits
        set used_in_answer = true,
            exclusion_reason = null
        where retrieval_run_id = $1
          and chunk_id = any($2::uuid[])
      `,
      [retrievalRunId, chunkIds]
    );
  }

  async insertAnswerCitations(values: AnswerCitationInsertRecord[], db: DatabaseExecutor = this.pool): Promise<void> {
    if (values.length === 0) {
      return;
    }

    const params: unknown[] = [];
    const rows = values
      .map((value) => {
        const base = params.length;
        params.push(
          randomUUID(),
          value.sessionId,
          value.documentId,
          value.chunkId,
          value.claimSlot,
          value.quoteText,
          value.locator
        );
        return `($${base + 1}, $${base + 2}, $${base + 3}, $${base + 4}, $${base + 5}, $${base + 6}, $${base + 7})`;
      })
      .join(", ");

    await db.query(
      `
        insert into answer_citations (
          id,
          session_id,
          document_id,
          chunk_id,
          claim_slot,
          quote_text,
          locator
        ) values ${rows}
      `,
      params
    );
  }

  async listChunksByIds(chunkIds: string[], db: DatabaseExecutor = this.pool): Promise<AnswerChunkRecord[]> {
    if (chunkIds.length === 0) {
      return [];
    }

    const result = await db.query<AnswerChunkRow>(
      `
        select
          c.id as "chunkId",
          c.document_id as "documentId",
          d.title as "documentTitle",
          c.chunk_index as "chunkIndex",
          c.section_label as "sectionLabel",
          c.page_ref as "pageRef",
          c.content_text as "contentText",
          c.content_sha256 as "contentSha256",
          c.citation_locator as "citationLocator"
        from document_chunks c
        inner join documents d on d.id = c.document_id
        where c.id = any($1::uuid[])
        order by array_position($1::uuid[], c.id)
      `,
      [chunkIds]
    );

    return result.rows.map((row) => ({
      id: row.chunkId,
      documentId: row.documentId,
      documentTitle: row.documentTitle,
      chunkIndex: row.chunkIndex,
      sectionLabel: row.sectionLabel,
      pageRef: row.pageRef,
      contentText: row.contentText,
      contentSha256: row.contentSha256,
      citationLocator: row.citationLocator
    }));
  }
}

interface RetrievalChunkCandidateRow {
  chunkId: string;
  documentId: string;
  documentTitle: string;
  chunkIndex: number;
  sectionLabel: string | null;
  pageRef: string | null;
  contentText: string;
  contentSha256: string;
  citationLocator: Record<string, unknown> | null;
  score: string | number;
}

interface RetrievalRunRow {
  id: string;
  sessionId: string;
  queryNormalized: string;
  eligibleDocumentCount: number;
  lexicalHitCount: number;
  semanticHitCount: number;
  mergedHitCount: number;
  rerankStrategy: string;
  latencyMs: number | null;
  createdAt: Date;
}

interface AnswerChunkRow {
  chunkId: string;
  documentId: string;
  documentTitle: string;
  chunkIndex: number;
  sectionLabel: string | null;
  pageRef: string | null;
  contentText: string;
  contentSha256: string;
  citationLocator: Record<string, unknown> | null;
}

function buildChunkWhereClause(documentIds: string[] | null, params: unknown[]): string {
  const where = [
    "d.index_status = 'ready'",
    "d.citation_ready = true"
  ];

  if (documentIds && documentIds.length > 0) {
    params.push(documentIds);
    where.push(`c.document_id = any($${params.length}::uuid[])`);
  }

  return where.join(" and ");
}

function normalizeNumericText(value: string | number | null): string | number | null {
  if (value === null) {
    return null;
  }

  if (typeof value === "number") {
    return value.toFixed(4);
  }

  const trimmed = value.trim();
  return trimmed.length > 0 ? trimmed : null;
}

function serializeVector(values: readonly number[]): string {
  return `[${values.map((value) => Number(value).toFixed(6)).join(",")}]`;
}

function toNumber(value: string | number | null): number | null {
  if (value === null) {
    return null;
  }

  const numeric = typeof value === "number" ? value : Number(value);
  return Number.isFinite(numeric) ? numeric : null;
}
