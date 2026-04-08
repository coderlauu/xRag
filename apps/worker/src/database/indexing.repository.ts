import { randomUUID } from "node:crypto";
import type { Pool, PoolClient } from "pg";
import type { DiagnosisCode, DocumentIndexingJobName, IndexStatus, ParseStatus } from "@xrag/shared-types";

type DatabaseExecutor = Pick<Pool | PoolClient, "query">;

export interface IndexingDocumentRecord {
  id: string;
  title: string;
  contentRaw: string | null;
  contentClean: string | null;
  sourceUrl: string | null;
  fileName: string | null;
  mimeType: string | null;
  objectKey: string | null;
  parseStatus: ParseStatus;
  indexStatus: IndexStatus;
  indexVersion: string | null;
  indexedAt: Date | null;
  citationReady: boolean;
  diagnosisCode: DiagnosisCode | null;
  diagnosisSummary: string | null;
  pageCount: number | null;
  parserName: string | null;
  parserVersion: string | null;
}

export interface IndexingJobRecord {
  id: string;
  document_id: string;
  job_type: DocumentIndexingJobName;
  status: "queued" | "running" | "succeeded" | "failed" | "dead";
  queue_job_id: string | null;
  attempt: number;
  started_at: Date | null;
}

export interface InsertDocumentChunkInput {
  chunkIndex: number;
  strategyVersion: string;
  sectionLabel: string | null;
  pageRef: string | null;
  contentText: string;
  tokenCount: number;
  contentSha256: string;
  citationLocator: Record<string, unknown>;
}

export interface DocumentChunkRecord extends InsertDocumentChunkInput {
  id: string;
  documentId: string;
  embedding: string | null;
  createdAt: Date;
}

export class IndexingRepository {
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

  async findJob(queueJobId: string | undefined, documentId: string, jobType: DocumentIndexingJobName, db: DatabaseExecutor = this.pool): Promise<IndexingJobRecord | null> {
    if (queueJobId) {
      const queueMatch = await db.query<IndexingJobRecord>(
        `
          select id, document_id, job_type, status, queue_job_id, attempt, started_at
          from document_parse_jobs
          where queue_job_id = $1
          limit 1
        `,
        [queueJobId]
      );

      if (queueMatch.rowCount) {
        return queueMatch.rows[0];
      }
    }

    const fallback = await db.query<IndexingJobRecord>(
      `
        select id, document_id, job_type, status, queue_job_id, attempt, started_at
        from document_parse_jobs
        where document_id = $1 and job_type = $2
        order by created_at desc
        limit 1
      `,
      [documentId, jobType]
    );

    return fallback.rowCount ? fallback.rows[0] : null;
  }

  async findDocument(documentId: string, db: DatabaseExecutor = this.pool): Promise<IndexingDocumentRecord | null> {
    const result = await db.query<IndexingDocumentRecord>(
      `
        select
          id,
          title,
          content_raw as "contentRaw",
          content_clean as "contentClean",
          source_url as "sourceUrl",
          file_name as "fileName",
          mime_type as "mimeType",
          object_key as "objectKey",
          parse_status as "parseStatus",
          index_status as "indexStatus",
          index_version as "indexVersion",
          indexed_at as "indexedAt",
          citation_ready as "citationReady",
          diagnosis_code as "diagnosisCode",
          diagnosis_summary as "diagnosisSummary",
          page_count as "pageCount",
          parser_name as "parserName",
          parser_version as "parserVersion"
        from documents
        where id = $1
        limit 1
      `,
      [documentId]
    );

    return result.rowCount ? result.rows[0] : null;
  }

  async getNextAttempt(documentId: string, db: DatabaseExecutor = this.pool): Promise<number> {
    const result = await db.query<{ attempt: number }>(
      `
        select attempt
        from document_parse_jobs
        where document_id = $1
        order by attempt desc
        limit 1
      `,
      [documentId]
    );

    return (result.rows[0]?.attempt ?? 0) + 1;
  }

  async createJob(values: {
    id: string;
    documentId: string;
    jobType: DocumentIndexingJobName;
    status: "queued" | "running" | "succeeded" | "failed" | "dead";
    attempt: number;
    queueJobId?: string | null;
  }, db: DatabaseExecutor = this.pool): Promise<IndexingJobRecord> {
    const result = await db.query<IndexingJobRecord>(
      `
        insert into document_parse_jobs (
          id,
          document_id,
          job_type,
          status,
          queue_job_id,
          attempt
        )
        values ($1, $2, $3, $4, $5, $6)
        returning id, document_id, job_type, status, queue_job_id, attempt, started_at
      `,
      [values.id, values.documentId, values.jobType, values.status, values.queueJobId ?? null, values.attempt]
    );

    return result.rows[0];
  }

  async updateJobQueueId(jobId: string, queueJobId: string, db: DatabaseExecutor = this.pool) {
    await db.query(
      `
        update document_parse_jobs
        set queue_job_id = $2
        where id = $1
      `,
      [jobId, queueJobId]
    );
  }

  async markJobRunning(jobId: string, queueJobId: string | null, db: DatabaseExecutor = this.pool) {
    await db.query(
      `
        update document_parse_jobs
        set status = 'running',
            queue_job_id = coalesce($2, queue_job_id),
            started_at = now(),
            error_message = null,
            error_code = null,
            diagnosis_code = null,
            incident_ref = null,
            runtime_ms = null,
            worker_name = 'document-indexing-worker'
        where id = $1
      `,
      [jobId, queueJobId]
    );
  }

  async markJobCompleted(jobId: string, db: DatabaseExecutor = this.pool) {
    await db.query(
      `
        update document_parse_jobs
        set status = 'succeeded',
            finished_at = now(),
            error_message = null,
            error_code = null,
            diagnosis_code = null,
            runtime_ms = coalesce(floor(extract(epoch from (now() - started_at)) * 1000)::int, runtime_ms)
        where id = $1
      `,
      [jobId]
    );
  }

  async markJobFailed(jobId: string, message: string, diagnosisCode: DiagnosisCode | null, dead = false, db: DatabaseExecutor = this.pool) {
    await db.query(
      `
        update document_parse_jobs
        set status = $2,
            finished_at = now(),
            error_message = $3,
            diagnosis_code = $4,
            runtime_ms = coalesce(floor(extract(epoch from (now() - started_at)) * 1000)::int, runtime_ms)
        where id = $1
      `,
      [jobId, dead ? "dead" : "failed", message, diagnosisCode]
    );
  }

  async markDocumentIndexChunking(documentId: string, indexVersion: string, db: DatabaseExecutor = this.pool) {
    await db.query(
      `
        update documents
        set index_status = 'chunking',
            index_version = $2,
            indexed_at = null,
            citation_ready = false,
            diagnosis_code = null,
            diagnosis_summary = null,
            updated_at = now()
        where id = $1
      `,
      [documentId, indexVersion]
    );
  }

  async markDocumentIndexEmbedding(documentId: string, indexVersion: string, db: DatabaseExecutor = this.pool) {
    await db.query(
      `
        update documents
        set index_status = 'embedding',
            index_version = $2,
            citation_ready = false,
            diagnosis_code = null,
            diagnosis_summary = null,
            updated_at = now()
        where id = $1
      `,
      [documentId, indexVersion]
    );
  }

  async markDocumentIndexReady(documentId: string, indexVersion: string, db: DatabaseExecutor = this.pool) {
    await db.query(
      `
        update documents
        set index_status = 'ready',
            index_version = $2,
            indexed_at = now(),
            citation_ready = true,
            diagnosis_code = null,
            diagnosis_summary = null,
            updated_at = now()
        where id = $1
      `,
      [documentId, indexVersion]
    );
  }

  async markDocumentIndexFailed(
    documentId: string,
    message: string,
    diagnosisCode: DiagnosisCode | null,
    indexVersion: string | null,
    db: DatabaseExecutor = this.pool
  ) {
    await db.query(
      `
        update documents
        set index_status = 'failed',
            index_version = $4,
            indexed_at = null,
            citation_ready = false,
            diagnosis_code = $3,
            diagnosis_summary = $2,
            updated_at = now()
        where id = $1
      `,
      [documentId, message, diagnosisCode, indexVersion]
    );
  }

  async markDocumentIndexStale(documentId: string, indexVersion: string, db: DatabaseExecutor = this.pool) {
    await db.query(
      `
        update documents
        set index_status = 'stale',
            index_version = $2,
            citation_ready = false,
            diagnosis_code = null,
            diagnosis_summary = null,
            updated_at = now()
        where id = $1
      `,
      [documentId, indexVersion]
    );
  }

  async deleteDocumentChunks(documentId: string, db: DatabaseExecutor = this.pool) {
    await db.query(
      `
        delete from document_chunks
        where document_id = $1
      `,
      [documentId]
    );
  }

  async insertDocumentChunks(
    documentId: string,
    chunks: InsertDocumentChunkInput[],
    db: DatabaseExecutor = this.pool
  ): Promise<DocumentChunkRecord[]> {
    const inserted: DocumentChunkRecord[] = [];

    for (const chunk of chunks) {
      const id = randomUUID();
      const result = await db.query<DocumentChunkRecord>(
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
            citation_locator
          )
          values ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10::jsonb)
          returning
            id,
            document_id as "documentId",
            chunk_index as "chunkIndex",
            strategy_version as "strategyVersion",
            section_label as "sectionLabel",
            page_ref as "pageRef",
            content_text as "contentText",
            token_count as "tokenCount",
            content_sha256 as "contentSha256",
            embedding,
            citation_locator as "citationLocator",
            created_at as "createdAt"
        `,
        [
          id,
          documentId,
          chunk.chunkIndex,
          chunk.strategyVersion,
          chunk.sectionLabel,
          chunk.pageRef,
          chunk.contentText,
          chunk.tokenCount,
          chunk.contentSha256,
          JSON.stringify(chunk.citationLocator)
        ]
      );

      inserted.push(result.rows[0]);
    }

    return inserted;
  }

  async listDocumentChunks(documentId: string, db: DatabaseExecutor = this.pool): Promise<DocumentChunkRecord[]> {
    const result = await db.query<DocumentChunkRecord>(
      `
        select
          id,
          document_id as "documentId",
          chunk_index as "chunkIndex",
          strategy_version as "strategyVersion",
          section_label as "sectionLabel",
          page_ref as "pageRef",
          content_text as "contentText",
          token_count as "tokenCount",
          content_sha256 as "contentSha256",
          embedding,
          citation_locator as "citationLocator",
          created_at as "createdAt"
        from document_chunks
        where document_id = $1
        order by chunk_index asc
      `,
      [documentId]
    );

    return result.rows;
  }

  async updateChunkEmbedding(
    chunkId: string,
    embedding: number[],
    db: DatabaseExecutor = this.pool
  ) {
    await db.query(
      `
        update document_chunks
        set embedding = $2::vector
        where id = $1
      `,
      [chunkId, serializeVector(embedding)]
    );
  }

  async createProcessingEvent(
    values: {
      documentId: string;
      eventType: string;
      stage: "upload" | "parse" | "ocr" | "fetch" | "projection" | "ops" | "index";
      status: ParseStatus;
      summary: string;
      diagnosisCode?: DiagnosisCode | null;
      payload?: Record<string, unknown> | null;
    },
    db: DatabaseExecutor = this.pool
  ) {
    const eventId = randomUUID();
    await db.query(
      `
        insert into document_processing_events (
          id,
          document_id,
          event_type,
          stage,
          status,
          diagnosis_code,
          summary,
          payload
        )
        values ($1, $2, $3, $4, $5, $6, $7, $8::jsonb)
      `,
      [
        eventId,
        values.documentId,
        values.eventType,
        values.stage,
        values.status,
        values.diagnosisCode ?? null,
        values.summary,
        values.payload ? JSON.stringify(values.payload) : null
      ]
    );
  }
}

function serializeVector(values: number[]): string {
  return `[${values.map((value) => Number(value).toString()).join(",")}]`;
}
