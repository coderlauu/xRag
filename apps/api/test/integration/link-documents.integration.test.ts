import assert from "node:assert/strict";
import { createServer } from "node:http";
import test from "node:test";
import { Pool } from "pg";
import { createApp } from "../../src/bootstrap";
import { WorkerRepository } from "../../../../apps/worker/src/database/repository";
import { createDocumentProcessingHandlers } from "../../../../apps/worker/src/jobs/document-processing";
import { DOCUMENT_PROCESSING_JOB_NAMES } from "../../../../apps/worker/src/queue/constants";
import { WorkerStorageService } from "../../../../apps/worker/src/storage/storage";

const databaseUrl = process.env.DATABASE_URL || "postgresql://xrag:xrag@127.0.0.1:5432/xrag";

async function resetDatabase() {
  const pool = new Pool({
    connectionString: databaseUrl
  });

  try {
    await pool.query(
      "truncate table document_processing_events, document_source_fetches, upload_parts, document_parse_jobs, document_tags, uploads, tags, documents restart identity cascade"
    );
  } finally {
    await pool.end();
  }
}

async function runLatestFetchJob(documentId: string) {
  const pool = new Pool({
    connectionString: databaseUrl
  });
  const storage = new WorkerStorageService();
  const handlers = createDocumentProcessingHandlers({
    repository: new WorkerRepository(pool),
    storage,
    logger: {
      debug: () => {},
      info: () => {},
      warn: () => {},
      error: () => {}
    },
    enqueueChunkDocument: async () => "integration-index-job"
  });

  try {
    return await handlers[DOCUMENT_PROCESSING_JOB_NAMES.fetchLink]({
      name: DOCUMENT_PROCESSING_JOB_NAMES.fetchLink,
      id: "integration-fetch-job",
      data: {
        documentId
      },
      attemptsMade: 0
    });
  } finally {
    storage.destroy();
    await pool.end();
  }
}

function startHtmlServer(html: string) {
  return new Promise<{ baseUrl: string; close: () => Promise<void> }>((resolve, reject) => {
    const server = createServer((_request, response) => {
      response.statusCode = 200;
      response.setHeader("content-type", "text/html; charset=utf-8");
      response.end(html);
    });

    server.once("error", reject);
    server.listen(0, "127.0.0.1", () => {
      const address = server.address();
      if (!address || typeof address === "string") {
        reject(new Error("Failed to bind test HTML server"));
        return;
      }

      resolve({
        baseUrl: `http://127.0.0.1:${address.port}`,
        close: () =>
          new Promise<void>((closeResolve, closeReject) => {
            server.close((error) => {
              if (error) {
                closeReject(error);
                return;
              }

              closeResolve();
            });
          })
      });
    });
  });
}

test("link documents API creates a link document, fetches body text and exposes timeline", async () => {
  await resetDatabase();
  const htmlServer = await startHtmlServer(`
    <!doctype html>
    <html lang="zh-CN">
      <head>
        <title>路线图公告</title>
      </head>
      <body>
        <article>
          <p>这里是抓取到的第一段正文。</p>
          <p>这里是抓取到的第二段正文。</p>
        </article>
      </body>
    </html>
  `);
  const app = await createApp();
  await app.init();

  try {
    const createResponse = await app.inject({
      method: "POST",
      url: "/api/v1/documents/link",
      payload: {
        source_url: `${htmlServer.baseUrl}/roadmap`,
        tags: ["路线图", "链接"]
      }
    });

    assert.equal(createResponse.statusCode, 201);
    const created = createResponse.json();
    assert.equal(created.parse_status, "pending");

    const jobResult = await runLatestFetchJob(created.id);
    assert.equal(jobResult.status, "success");

    const detailResponse = await app.inject({
      method: "GET",
      url: `/api/v1/documents/${created.id}`
    });

    assert.equal(detailResponse.statusCode, 200);
    const detail = detailResponse.json();
    assert.equal(detail.parse_status, "success");
    assert.equal(detail.index_status, "queued");
    assert.match(detail.content_preview, /抓取到的第一段正文/);
    assert.equal(detail.source_type, "link");

    const timelineResponse = await app.inject({
      method: "GET",
      url: `/api/v1/documents/${created.id}/timeline`
    });

    assert.equal(timelineResponse.statusCode, 200);
    const timeline = timelineResponse.json();
    assert.equal(timeline.items.length >= 2, true);
    assert.equal(timeline.items.some((item: { event_type: string }) => item.event_type === "link_fetch_started"), true);
    assert.equal(timeline.items.some((item: { event_type: string }) => item.event_type === "link_fetch_succeeded"), true);
    assert.equal(timeline.items.some((item: { event_type: string }) => item.event_type === "index_queued"), true);
  } finally {
    await app.close();
    await htmlServer.close();
  }
});
