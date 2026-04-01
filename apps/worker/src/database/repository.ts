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

export interface WorkerJobRecord {
  id: string;
  document_id: string;
  job_type: string;
  status: string;
  queue_job_id: string | null;
  attempt: number;
}

export class WorkerRepository {
  constructor(private readonly pool: Pool) {}

  async findJob(queueJobId: string | undefined, documentId: string, jobType: string): Promise<WorkerJobRecord | null> {
    if (queueJobId) {
      const queueMatch = await this.pool.query<WorkerJobRecord>(
        `
          select id, document_id, job_type, status, queue_job_id, attempt
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
        select id, document_id, job_type, status, queue_job_id, attempt
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
            error_code = null
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
            error_code = null
        where id = $1
      `,
      [jobId]
    );
  }

  async markJobFailed(jobId: string, message: string, dead = false) {
    await this.pool.query(
      `
        update document_parse_jobs
        set status = $2,
            finished_at = now(),
            error_message = $3
        where id = $1
      `,
      [jobId, dead ? "dead" : "failed", message]
    );
  }

  async markDocumentProcessing(documentId: string) {
    await this.pool.query(
      `
        update documents
        set parse_status = 'processing',
            parse_error_message = null,
            updated_at = now()
        where id = $1
      `,
      [documentId]
    );
  }

  async markDocumentSuccess(documentId: string, values: {
    contentRaw: string | null;
    contentClean: string;
    contentPreview: string;
    searchText: string;
  }) {
    await this.pool.query(
      `
        update documents
        set content_raw = coalesce($2, content_raw),
            content_clean = $3,
            content_preview = $4,
            search_text = $5,
            search_vector = to_tsvector('simple', $5),
            parse_status = 'success',
            parse_error_message = null,
            updated_at = now()
        where id = $1
      `,
      [documentId, values.contentRaw, values.contentClean, values.contentPreview, values.searchText]
    );
  }

  async markDocumentFailed(documentId: string, message: string) {
    await this.pool.query(
      `
        update documents
        set parse_status = 'failed',
            parse_error_message = $2,
            updated_at = now()
        where id = $1
      `,
      [documentId, message]
    );
  }
}
