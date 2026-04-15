import { randomUUID } from "node:crypto";
import { Pool } from "pg";
import { loadApiEnv } from "../config/env";
import { QueueService } from "../queue/queue.service";

interface Args {
  dryRun: boolean;
  limit: number;
  statuses: string[];
}

interface CandidateDocument {
  id: string;
  title: string;
  indexStatus: string;
}

function parseArgs(argv: string[]): Args {
  const parsed: Args = {
    dryRun: false,
    limit: 200,
    statuses: ["not_indexed"]
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];

    if (arg === "--dry-run") {
      parsed.dryRun = true;
      continue;
    }

    if (arg === "--limit") {
      parsed.limit = Number.parseInt(argv[index + 1] || "", 10) || parsed.limit;
      index += 1;
      continue;
    }

    if (arg === "--statuses") {
      parsed.statuses = (argv[index + 1] || parsed.statuses.join(","))
        .split(",")
        .map((value) => value.trim())
        .filter(Boolean);
      index += 1;
    }
  }

  return parsed;
}

async function listCandidates(pool: Pool, args: Args): Promise<CandidateDocument[]> {
  const result = await pool.query<CandidateDocument>(
    `
      select
        id,
        title,
        index_status::text as "indexStatus"
      from documents
      where parse_status = 'success'
        and index_status::text = any($1::text[])
      order by imported_at asc, created_at asc
      limit $2
    `,
    [args.statuses, args.limit]
  );

  return result.rows;
}

async function queueIndexingJob(pool: Pool, queueService: QueueService, document: CandidateDocument) {
  const client = await pool.connect();
  let jobId = "";

  try {
    await client.query("begin");
    const attemptResult = await client.query<{ attempt: number }>(
      `
        select attempt
        from document_parse_jobs
        where document_id = $1
        order by attempt desc
        limit 1
      `,
      [document.id]
    );
    const attempt = (attemptResult.rows[0]?.attempt ?? 0) + 1;
    jobId = randomUUID();

    await client.query(
      `
        insert into document_parse_jobs (
          id,
          document_id,
          job_type,
          status,
          queue_job_id,
          attempt
        )
        values ($1, $2, 'chunk_document', 'queued', null, $3)
      `,
      [jobId, document.id, attempt]
    );

    const updateResult = await client.query(
      `
        update documents
        set index_status = 'queued',
            citation_ready = false,
            diagnosis_code = null,
            diagnosis_summary = null,
            updated_at = now()
        where id = $1
          and parse_status = 'success'
          and index_status::text = any($2::text[])
      `,
      [document.id, [document.indexStatus]]
    );

    if (updateResult.rowCount !== 1) {
      await client.query("rollback");
      return {
        status: "skipped" as const
      };
    }

    await client.query(
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
        values ($1, $2, 'index_queued', 'index', 'pending', null, $3, $4::jsonb)
      `,
      [
        randomUUID(),
        document.id,
        "恢复脚本已创建问答索引回补任务。",
        JSON.stringify({ job_id: jobId, source: "recovery_backfill" })
      ]
    );

    await client.query("commit");
  } catch (error) {
    await client.query("rollback");
    throw error;
  } finally {
    client.release();
  }

  try {
    const queueJobId = await queueService.enqueueChunkDocument(document.id, jobId);
    try {
      await pool.query(
        `
          update document_parse_jobs
          set queue_job_id = $2
          where id = $1
        `,
        [jobId, queueJobId]
      );
    } catch (error) {
      const message = error instanceof Error ? error.message : "failed to persist queue job id";
      process.stderr.write(`warning ${document.id}: queued job ${queueJobId} but failed to persist queue_job_id: ${message}\n`);
    }

    return {
      status: "queued" as const,
      jobId
    };
  } catch (error) {
    const message = error instanceof Error ? error.message : "failed to enqueue indexing job";
    await pool.query(
      `
        update document_parse_jobs
        set status = 'failed',
            error_code = 'queue_backlog',
            error_message = $2,
            diagnosis_code = 'queue_backlog',
            finished_at = now()
        where id = $1
      `,
      [jobId, message]
    );
    await pool.query(
      `
        update documents
        set index_status = 'failed',
            citation_ready = false,
            diagnosis_code = 'queue_backlog',
            diagnosis_summary = '索引任务未能入队，请稍后重试。',
            updated_at = now()
        where id = $1
      `,
      [document.id]
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
          payload
        )
        values ($1, $2, 'index_enqueue_failed', 'index', 'failed', 'queue_backlog', '问答索引任务入队失败。', $3::jsonb)
      `,
      [randomUUID(), document.id, JSON.stringify({ job_id: jobId, source: "recovery_backfill" })]
    );

    return {
      status: "failed" as const,
      jobId,
      reason: message
    };
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  const env = loadApiEnv();
  const pool = new Pool({
    connectionString: env.databaseUrl,
    max: env.databasePoolMax
  });
  const queueService = new QueueService();

  try {
    const candidates = await listCandidates(pool, args);
    process.stdout.write(
      `Found ${candidates.length} parse-ready documents in statuses [${args.statuses.join(", ")}] (limit=${args.limit})\n`
    );

    if (args.dryRun) {
      for (const candidate of candidates) {
        process.stdout.write(`${candidate.id} ${candidate.indexStatus} ${candidate.title}\n`);
      }
      return;
    }

    let queued = 0;
    let skipped = 0;
    let failed = 0;

    for (const candidate of candidates) {
      const result = await queueIndexingJob(pool, queueService, candidate);
      if (result.status === "queued") {
        queued += 1;
        process.stdout.write(`queued ${candidate.id} -> ${result.jobId}\n`);
        continue;
      }

      if (result.status === "skipped") {
        skipped += 1;
        process.stdout.write(`skipped ${candidate.id} (state changed while processing)\n`);
        continue;
      }

      failed += 1;
      process.stdout.write(`failed ${candidate.id}: ${result.reason}\n`);
    }

    process.stdout.write(`done queued=${queued} skipped=${skipped} failed=${failed}\n`);
  } finally {
    await queueService.onApplicationShutdown();
    await pool.end();
  }
}

void main();
