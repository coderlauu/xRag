import { randomUUID } from "node:crypto";
import type { Pool } from "pg";

export interface WorkerDocumentRecord {
  id: string;
  title: string;
  content_raw: string | null;
  content_clean: string | null;
  source_url: string | null;
  file_name: string | null;
  mime_type: string | null;
  object_key: string | null;
}

export interface WorkerSourceFetchRecord {
  id: string;
  document_id: string;
  source_url: string;
  fetch_status: "queued" | "fetching" | "extracting" | "success" | "failed";
}

export interface WorkerJobRecord {
  id: string;
  document_id: string;
  job_type: string;
  status: string;
  queue_job_id: string | null;
  attempt: number;
  started_at?: Date | null;
}

export interface WorkerProcessingJobInsert {
  id: string;
  documentId: string;
  jobType: string;
  status: "queued" | "running" | "succeeded" | "failed" | "dead";
  attempt: number;
  queueJobId?: string | null;
}

export class WorkerRepository {
  constructor(private readonly pool: Pool) {}

  async findJob(queueJobId: string | undefined, documentId: string, jobType: string): Promise<WorkerJobRecord | null> {
    if (queueJobId) {
      const queueMatch = await this.pool.query<WorkerJobRecord>(
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

    const fallback = await this.pool.query<WorkerJobRecord>(
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

  async findDocument(documentId: string): Promise<WorkerDocumentRecord | null> {
    const result = await this.pool.query<WorkerDocumentRecord>(
      `
        select id, title, content_raw, content_clean, source_url, file_name, mime_type, object_key
        from documents
        where id = $1
        limit 1
      `,
      [documentId]
    );

    return result.rowCount ? result.rows[0] : null;
  }

  async listDocumentTags(documentId: string): Promise<string[]> {
    const result = await this.pool.query<{ name: string }>(
      `
        select t.name
        from document_tags dt
        inner join tags t on t.id = dt.tag_id
        where dt.document_id = $1
        order by t.name asc
      `,
      [documentId]
    );

    return result.rows.map((row) => row.name);
  }

  async markJobRunning(jobId: string, queueJobId: string | null) {
    await this.pool.query(
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
            worker_name = 'document-processing-worker'
        where id = $1
      `,
      [jobId, queueJobId]
    );
  }

  async markJobCompleted(jobId: string) {
    await this.pool.query(
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

  async markJobFailed(jobId: string, message: string, diagnosisCode: string | null, dead = false) {
    await this.pool.query(
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

  async markDocumentProcessing(documentId: string) {
    await this.pool.query(
      `
        update documents
        set parse_status = 'processing',
            parse_error_message = null,
            diagnosis_code = null,
            diagnosis_summary = null,
            updated_at = now()
        where id = $1
      `,
      [documentId]
    );
  }

  async markDocumentSuccess(documentId: string, values: {
    title?: string | null;
    contentRaw: string | null;
    contentClean: string;
    contentPreview: string;
    searchText: string;
    mimeType?: string | null;
    pageCount?: number | null;
    parserName?: string | null;
    parserVersion?: string | null;
  }) {
    await this.pool.query(
      `
        update documents
        set title = coalesce($2, title),
            content_raw = coalesce($3, content_raw),
            content_clean = $4,
            content_preview = $5,
            search_text = $6,
            search_vector = to_tsvector('simple', $6),
            mime_type = coalesce($7, mime_type),
            page_count = coalesce($8, page_count),
            parser_name = coalesce($9, parser_name),
            parser_version = coalesce($10, parser_version),
            parse_status = 'success',
            parse_error_message = null,
            diagnosis_code = null,
            diagnosis_summary = null,
            updated_at = now()
        where id = $1
      `,
      [
        documentId,
        values.title ?? null,
        values.contentRaw,
        values.contentClean,
        values.contentPreview,
        values.searchText,
        values.mimeType ?? null,
        values.pageCount ?? null,
        values.parserName ?? null,
        values.parserVersion ?? null
      ]
    );
  }

  async markDocumentOcrQueued(documentId: string) {
    await this.pool.query(
      `
        update documents
        set parse_status = 'pending',
            ocr_status = 'queued',
            parse_error_message = null,
            diagnosis_code = null,
            diagnosis_summary = null,
            updated_at = now()
        where id = $1
      `,
      [documentId]
    );
  }

  async markDocumentOcrProcessing(documentId: string) {
    await this.pool.query(
      `
        update documents
        set parse_status = 'processing',
            ocr_status = 'processing',
            parse_error_message = null,
            diagnosis_code = null,
            diagnosis_summary = null,
            updated_at = now()
        where id = $1
      `,
      [documentId]
    );
  }

  async markDocumentOcrSuccess(documentId: string, values: {
    contentRaw: string | null;
    contentClean: string;
    contentPreview: string;
    searchText: string;
    pageCount?: number | null;
    ocrEngine: string;
    ocrLanguage: string;
  }) {
    await this.pool.query(
      `
        update documents
        set content_raw = coalesce($2, content_raw),
            content_clean = $3,
            content_preview = $4,
            search_text = $5,
            search_vector = to_tsvector('simple', $5),
            page_count = coalesce($6, page_count),
            ocr_status = 'success',
            ocr_engine = $7,
            ocr_language = $8,
            parse_status = 'success',
            parse_error_message = null,
            diagnosis_code = null,
            diagnosis_summary = null,
            updated_at = now()
        where id = $1
      `,
      [
        documentId,
        values.contentRaw,
        values.contentClean,
        values.contentPreview,
        values.searchText,
        values.pageCount ?? null,
        values.ocrEngine,
        values.ocrLanguage
      ]
    );
  }

  async markDocumentFailed(documentId: string, message: string, diagnosisCode: string | null) {
    await this.pool.query(
      `
        update documents
        set parse_status = 'failed',
            parse_error_message = $2,
            diagnosis_code = $3,
            diagnosis_summary = $2,
            updated_at = now()
        where id = $1
      `,
      [documentId, message, diagnosisCode]
    );
  }

  async markDocumentOcrFailed(
    documentId: string,
    message: string,
    diagnosisCode: string | null,
    values?: {
      ocrEngine?: string | null;
      ocrLanguage?: string | null;
    }
  ) {
    await this.pool.query(
      `
        update documents
        set parse_status = 'failed',
            ocr_status = 'failed',
            ocr_engine = coalesce($4, ocr_engine),
            ocr_language = coalesce($5, ocr_language),
            parse_error_message = $2,
            diagnosis_code = $3,
            diagnosis_summary = $2,
            updated_at = now()
        where id = $1
      `,
      [documentId, message, diagnosisCode, values?.ocrEngine ?? null, values?.ocrLanguage ?? null]
    );
  }

  async getNextAttempt(documentId: string): Promise<number> {
    const result = await this.pool.query<{ attempt: number }>(
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

  async createJob(values: WorkerProcessingJobInsert): Promise<WorkerJobRecord> {
    const result = await this.pool.query<WorkerJobRecord>(
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

  async updateJobQueueId(jobId: string, queueJobId: string) {
    await this.pool.query(
      `
        update document_parse_jobs
        set queue_job_id = $2
        where id = $1
      `,
      [jobId, queueJobId]
    );
  }

  async createSourceFetch(documentId: string, sourceUrl: string): Promise<WorkerSourceFetchRecord> {
    const fetchId = randomUUID();
    const result = await this.pool.query<WorkerSourceFetchRecord>(
      `
        insert into document_source_fetches (id, document_id, source_url, fetch_status)
        values ($1, $2, $3, 'queued')
        returning id, document_id, source_url, fetch_status
      `,
      [fetchId, documentId, sourceUrl]
    );

    return result.rows[0];
  }

  async markSourceFetchRunning(fetchId: string) {
    await this.pool.query(
      `
        update document_source_fetches
        set fetch_status = 'fetching',
            started_at = now(),
            error_message = null,
            diagnosis_code = null
        where id = $1
      `,
      [fetchId]
    );
  }

  async markSourceFetchSucceeded(fetchId: string, values: {
    contentType: string | null;
    canonicalUrl: string | null;
    titleExtracted: string | null;
  }) {
    await this.pool.query(
      `
        update document_source_fetches
        set fetch_status = 'success',
            content_type = $2,
            canonical_url = $3,
            title_extracted = $4,
            finished_at = now()
        where id = $1
      `,
      [fetchId, values.contentType, values.canonicalUrl, values.titleExtracted]
    );
  }

  async markSourceFetchFailed(
    fetchId: string,
    values: {
      diagnosisCode: string | null;
      errorMessage: string;
      httpStatus?: number | null;
      contentType?: string | null;
    }
  ) {
    await this.pool.query(
      `
        update document_source_fetches
        set fetch_status = 'failed',
            diagnosis_code = $2,
            error_message = $3,
            http_status = $4,
            content_type = $5,
            finished_at = now()
        where id = $1
      `,
      [fetchId, values.diagnosisCode, values.errorMessage, values.httpStatus ?? null, values.contentType ?? null]
    );
  }

  async createProcessingEvent(values: {
    documentId: string;
    eventType: string;
    stage: "upload" | "parse" | "ocr" | "fetch" | "projection" | "ops";
    status: "pending" | "processing" | "success" | "failed";
    summary: string;
    diagnosisCode?: string | null;
    payload?: Record<string, unknown> | null;
  }) {
    const eventId = randomUUID();
    await this.pool.query(
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
