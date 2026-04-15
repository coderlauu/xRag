import test from "node:test";
import assert from "node:assert/strict";
import { Pool } from "pg";
import { createApp } from "../../src/bootstrap";
import { WorkerRepository } from "../../../../apps/worker/src/database/repository";
import { createDocumentProcessingHandlers } from "../../../../apps/worker/src/jobs/document-processing";
import { DOCUMENT_PROCESSING_JOB_NAMES } from "../../../../apps/worker/src/queue/constants";
import { WorkerStorageService } from "../../../../apps/worker/src/storage/storage";

const databaseUrl = process.env.DATABASE_URL || "postgresql://xrag:xrag@127.0.0.1:5432/xrag";

const SAMPLE_PDF_CONTENT = `%PDF-1.4
1 0 obj
<< /Type /Catalog /Pages 2 0 R >>
endobj
2 0 obj
<< /Type /Pages /Kids [3 0 R] /Count 1 >>
endobj
3 0 obj
<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 144] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>
endobj
4 0 obj
<< /Length 44 >>
stream
BT
/F1 24 Tf
72 96 Td
(Hello PDF) Tj
ET
endstream
endobj
5 0 obj
<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>
endobj
xref
0 6
0000000000 65535 f 
0000000010 00000 n 
0000000063 00000 n 
0000000122 00000 n 
0000000248 00000 n 
0000000342 00000 n 
trailer
<< /Root 1 0 R /Size 6 >>
startxref
412
%%EOF`;
const SAMPLE_PDF_BYTES = Buffer.from(SAMPLE_PDF_CONTENT, "utf8");

async function resetDatabase() {
  const pool = new Pool({
    connectionString: databaseUrl
  });

  try {
    await pool.query(
      "truncate table upload_parts, document_parse_jobs, document_tags, uploads, tags, documents restart identity cascade"
    );
  } finally {
    await pool.end();
  }
}

async function runLatestParseJob(documentId: string) {
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
    return await handlers[DOCUMENT_PROCESSING_JOB_NAMES.parseDocument]({
      name: DOCUMENT_PROCESSING_JOB_NAMES.parseDocument,
      id: "integration-worker-job",
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

async function runLatestParseJobWithOverrides(
  documentId: string,
  overrides: Partial<Parameters<typeof createDocumentProcessingHandlers>[0]>
) {
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
    enqueueChunkDocument: async () => "integration-index-job",
    ...overrides
  });

  try {
    return await handlers[DOCUMENT_PROCESSING_JOB_NAMES.parseDocument]({
      name: DOCUMENT_PROCESSING_JOB_NAMES.parseDocument,
      id: "integration-worker-job",
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

async function runLatestOcrJob(
  documentId: string,
  overrides: Partial<Parameters<typeof createDocumentProcessingHandlers>[0]> = {}
) {
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
    enqueueChunkDocument: async () => "integration-index-job",
    ...overrides
  });

  try {
    return await handlers[DOCUMENT_PROCESSING_JOB_NAMES.runOcr]({
      name: DOCUMENT_PROCESSING_JOB_NAMES.runOcr,
      id: "integration-ocr-job",
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

test("uploads API exposes the Phase 1B single-upload contract and document projection", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const content = "phase 1b single upload integration";
    const initiateResponse = await app.inject({
      method: "POST",
      url: "/api/v1/uploads/initiate",
      payload: {
        file_name: "phase-1b-upload.txt",
        mime_type: "text/plain",
        file_size: content.length,
        checksum_sha256: "a".repeat(64)
      }
    });

    assert.equal(initiateResponse.statusCode, 201);
    const initiated = initiateResponse.json();
    assert.equal(initiated.upload_mode, "single");
    assert.equal(initiated.status, "initiated");
    assert.ok(initiated.upload_url);

    const putResponse = await fetch(initiated.upload_url, {
      method: "PUT",
      headers: {
        "content-type": "text/plain"
      },
      body: content
    });
    assert.equal(putResponse.ok, true);

    const completeResponse = await app.inject({
      method: "POST",
      url: `/api/v1/uploads/${initiated.upload_id}/complete`,
      payload: {
        title: "Phase 1B upload integration",
        tags: ["integration", "upload"],
        checksum_sha256: "a".repeat(64)
      }
    });

    assert.equal(completeResponse.statusCode, 201);
    const completed = completeResponse.json();
    assert.equal(completed.upload_id, initiated.upload_id);
    assert.equal(completed.upload_status, "uploaded");
    assert.equal(completed.parse_status, "pending");
    assert.equal(completed.diagnosis_code, null);

    const detailResponse = await app.inject({
      method: "GET",
      url: `/api/v1/documents/${completed.document_id}`
    });
    assert.equal(detailResponse.statusCode, 200);
    const detail = detailResponse.json();
    assert.equal(detail.upload_status, "uploaded");
    assert.equal(detail.latest_job_status, "queued");
    assert.equal(detail.upload.upload_mode, "single");
    assert.equal(detail.upload.status, "uploaded");
    assert.equal(detail.latest_job.status, "queued");
  } finally {
    await app.close();
  }
});

test("uploads API exposes multipart session URLs and part completion contract", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const initiateResponse = await app.inject({
      method: "POST",
      url: "/api/v1/uploads/initiate",
      payload: {
        file_name: "phase-1b-large.pdf",
        mime_type: "application/pdf",
        file_size: 11 * 1024 * 1024
      }
    });

    assert.equal(initiateResponse.statusCode, 201);
    const initiated = initiateResponse.json();
    assert.equal(initiated.upload_mode, "multipart");
    assert.ok(initiated.part_count >= 3);
    assert.ok(initiated.provider_upload_id);

    const partsResponse = await app.inject({
      method: "POST",
      url: `/api/v1/uploads/${initiated.upload_id}/parts`,
      payload: {
        part_numbers: [1, 2]
      }
    });

    assert.equal(partsResponse.statusCode, 201);
    const parts = partsResponse.json();
    assert.equal(parts.parts.length, 2);
    assert.equal(parts.parts[0].part_number, 1);
    assert.ok(parts.parts[0].upload_url);

    const partCompleteResponse = await app.inject({
      method: "POST",
      url: `/api/v1/uploads/${initiated.upload_id}/parts/1/complete`,
      payload: {
        etag: "\"etag-1\"",
        size_bytes: 5 * 1024 * 1024
      }
    });

    assert.equal(partCompleteResponse.statusCode, 201);
    const completedPart = partCompleteResponse.json();
    assert.equal(completedPart.status, "uploaded");
    assert.equal(completedPart.uploaded_part_count, 1);
  } finally {
    await app.close();
  }
});

test("uploads API completes a multipart upload and projects document state", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const initiateResponse = await app.inject({
      method: "POST",
      url: "/api/v1/uploads/initiate",
      payload: {
        file_name: "phase-1b-complete.pdf",
        mime_type: "application/pdf",
        file_size: 11 * 1024 * 1024
      }
    });

    assert.equal(initiateResponse.statusCode, 201);
    const initiated = initiateResponse.json();
    assert.equal(initiated.upload_mode, "multipart");
    assert.equal(initiated.part_count, 3);

    const partsResponse = await app.inject({
      method: "POST",
      url: `/api/v1/uploads/${initiated.upload_id}/parts`,
      payload: {
        part_numbers: [1, 2, 3]
      }
    });

    assert.equal(partsResponse.statusCode, 201);
    const multipart = partsResponse.json();
    assert.equal(multipart.parts.length, 3);

    const payloads = [
      Buffer.alloc(5 * 1024 * 1024, "a"),
      Buffer.alloc(5 * 1024 * 1024, "b"),
      Buffer.alloc(1 * 1024 * 1024, "c")
    ];
    const completedParts: Array<{ part_number: number; etag: string }> = [];

    for (let index = 0; index < multipart.parts.length; index += 1) {
      const part = multipart.parts[index];
      const uploadResponse = await fetch(part.upload_url, {
        method: "PUT",
        body: payloads[index]
      });
      assert.equal(uploadResponse.ok, true);

      const etag = uploadResponse.headers.get("etag");
      assert.ok(etag);

      const partCompleteResponse = await app.inject({
        method: "POST",
        url: `/api/v1/uploads/${initiated.upload_id}/parts/${part.part_number}/complete`,
        payload: {
          etag,
          size_bytes: payloads[index].byteLength
        }
      });

      assert.equal(partCompleteResponse.statusCode, 201);
      completedParts.push({
        part_number: part.part_number,
        etag
      });
    }

    const completeResponse = await app.inject({
      method: "POST",
      url: `/api/v1/uploads/${initiated.upload_id}/complete`,
      payload: {
        title: "Phase 1B multipart integration",
        tags: ["integration", "multipart"],
        checksum_sha256: "b".repeat(64),
        parts: completedParts
      }
    });

    assert.equal(completeResponse.statusCode, 201);
    const completed = completeResponse.json();
    assert.equal(completed.upload_id, initiated.upload_id);
    assert.equal(completed.upload_status, "uploaded");
    assert.equal(completed.parse_status, "pending");
    assert.equal(completed.diagnosis_code, null);

    const detailResponse = await app.inject({
      method: "GET",
      url: `/api/v1/documents/${completed.document_id}`
    });
    assert.equal(detailResponse.statusCode, 200);
    const detail = detailResponse.json();
    assert.equal(detail.upload_status, "uploaded");
    assert.equal(detail.upload.upload_mode, "multipart");
    assert.equal(detail.upload.status, "uploaded");
    assert.equal(detail.upload.part_count, 3);
    assert.equal(detail.upload.uploaded_part_count, 3);
    assert.ok(detail.upload.verified_at);
    assert.equal(detail.latest_job.status, "queued");
  } finally {
    await app.close();
  }
});

test("uploads API plus worker parses PDF and exposes search/detail diagnostics", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const initiateResponse = await app.inject({
      method: "POST",
      url: "/api/v1/uploads/initiate",
      payload: {
        file_name: "phase-1b-sample.pdf",
        mime_type: "application/pdf",
        file_size: SAMPLE_PDF_BYTES.byteLength
      }
    });

    assert.equal(initiateResponse.statusCode, 201);
    const initiated = initiateResponse.json();
    assert.equal(initiated.upload_mode, "single");

    const putResponse = await fetch(initiated.upload_url, {
      method: "PUT",
      headers: {
        "content-type": "application/pdf"
      },
      body: SAMPLE_PDF_BYTES
    });
    assert.equal(putResponse.ok, true);

    const completeResponse = await app.inject({
      method: "POST",
      url: `/api/v1/uploads/${initiated.upload_id}/complete`,
      payload: {
        title: "Phase 1B PDF 回归样本",
        tags: ["pdf", "integration"],
        checksum_sha256: "c".repeat(64)
      }
    });

    assert.equal(completeResponse.statusCode, 201);
    const completed = completeResponse.json();
    assert.equal(completed.upload_status, "uploaded");
    assert.equal(completed.parse_status, "pending");

    const workerResult = await runLatestParseJob(completed.document_id);
    assert.equal(workerResult.status, "success", workerResult.reason);

    const detailResponse = await app.inject({
      method: "GET",
      url: `/api/v1/documents/${completed.document_id}`
    });
    assert.equal(detailResponse.statusCode, 200);
    const detail = detailResponse.json();
    assert.equal(detail.parse_status, "success");
    assert.equal(detail.index_status, "queued");
    assert.equal(detail.upload_status, "uploaded");
    assert.equal(detail.diagnosis_code, null);
    assert.equal(detail.latest_job.status, "queued");
    assert.equal(detail.page_count, 1);
    assert.equal(detail.parser_name, "pdf-parse");
    assert.match(detail.content_preview, /Hello PDF/);

    const listResponse = await app.inject({
      method: "GET",
      url: "/api/v1/documents?q=Hello%20PDF&source_type=file"
    });
    assert.equal(listResponse.statusCode, 200);
    const list = listResponse.json();
    assert.equal(list.total, 1);
    assert.equal(list.items[0].title, "Phase 1B PDF 回归样本");
    assert.equal(list.items[0].parse_status, "success");
    assert.equal(list.items[0].index_status, "queued");
    assert.equal(list.items[0].latest_job_status, "queued");
    assert.equal(list.items[0].page_count, 1);
    assert.equal(list.items[0].parser_name, "pdf-parse");
  } finally {
    await app.close();
  }
});

test("uploads API routes scanned PDFs into OCR and projects OCR text", async () => {
  await resetDatabase();
  const app = await createApp();
  await app.init();

  try {
    const initiateResponse = await app.inject({
      method: "POST",
      url: "/api/v1/uploads/initiate",
      payload: {
        file_name: "phase-1c-scanned.pdf",
        mime_type: "application/pdf",
        file_size: SAMPLE_PDF_BYTES.byteLength
      }
    });

    assert.equal(initiateResponse.statusCode, 201);
    const initiated = initiateResponse.json();

    const putResponse = await fetch(initiated.upload_url, {
      method: "PUT",
      headers: {
        "content-type": "application/pdf"
      },
      body: SAMPLE_PDF_BYTES
    });
    assert.equal(putResponse.ok, true);

    const completeResponse = await app.inject({
      method: "POST",
      url: `/api/v1/uploads/${initiated.upload_id}/complete`,
      payload: {
        title: "Phase 1C OCR 回归样本",
        tags: ["ocr", "integration"],
        checksum_sha256: "d".repeat(64)
      }
    });

    assert.equal(completeResponse.statusCode, 201);
    const completed = completeResponse.json();

    const parseResult = await runLatestParseJobWithOverrides(completed.document_id, {
      parsePdf: async () => {
        throw new Error("pdf extraction returned empty text");
      },
      enqueueOcr: async () => "integration-ocr-job"
    });
    assert.equal(parseResult.status, "success");

    const ocrResult = await runLatestOcrJob(completed.document_id, {
      runOcr: async () => ({
        text: "这是扫描件通过 OCR 识别出的正文。",
        pageCount: 1,
        ocrEngine: "tesseract-ocr",
        ocrLanguage: "chi_sim+eng"
      })
    });
    assert.equal(ocrResult.status, "success");

    const detailResponse = await app.inject({
      method: "GET",
      url: `/api/v1/documents/${completed.document_id}`
    });
    assert.equal(detailResponse.statusCode, 200);
    const detail = detailResponse.json();
    assert.equal(detail.parse_status, "success");
    assert.equal(detail.ocr_status, "success");
    assert.equal(detail.index_status, "queued");
    assert.equal(detail.ocr_engine, "tesseract-ocr");
    assert.equal(detail.ocr_language, "chi_sim+eng");
    assert.match(detail.content_preview, /OCR 识别出的正文/);

    const timelineResponse = await app.inject({
      method: "GET",
      url: `/api/v1/documents/${completed.document_id}/timeline`
    });
    assert.equal(timelineResponse.statusCode, 200);
    const timeline = timelineResponse.json();
    assert.equal(timeline.items.some((item: { event_type: string }) => item.event_type === "ocr_queued"), true);
    assert.equal(timeline.items.some((item: { event_type: string }) => item.event_type === "ocr_started"), true);
    assert.equal(timeline.items.some((item: { event_type: string }) => item.event_type === "ocr_succeeded"), true);
    assert.equal(timeline.items.some((item: { event_type: string }) => item.event_type === "index_queued"), true);
  } finally {
    await app.close();
  }
});
