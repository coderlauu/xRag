import { randomUUID } from "node:crypto";
import type { Logger } from "../logging/logger";
import type { EmbeddingProvider } from "../providers/contracts";
import { DOCUMENT_INDEXING_JOB_NAMES, type DocumentIndexingJobData, type DocumentIndexingJobName } from "../queue/constants";
import { chunkDocumentText, buildIndexVersion } from "../indexing/chunking";
import type { IndexingRepository, IndexingDocumentRecord } from "../database/indexing.repository";
import type { DiagnosisCode } from "@xrag/shared-types";

const INDEX_EMBEDDING_DIMENSIONS = 1536;
const INDEX_BATCH_SIZE = 16;

export interface DocumentIndexingJobResult {
  documentId: string;
  status: "success" | "failed" | "skipped";
  reason?: string;
}

type JobContext = {
  name: DocumentIndexingJobName;
  id: string;
  data: DocumentIndexingJobData;
  attemptsMade: number;
};

export interface DocumentIndexingDependencies {
  repository: IndexingRepository;
  logger: Logger;
  embeddingModel?: string;
  getEmbeddingProvider: () => EmbeddingProvider;
  enqueueEmbedDocument: (documentId: string, jobId: string) => Promise<string>;
}

export function createDocumentIndexingHandlers(deps: DocumentIndexingDependencies) {
  return {
    [DOCUMENT_INDEXING_JOB_NAMES.chunkDocument]: async (context: JobContext) => {
      return processChunkDocument(context, deps);
    },
    [DOCUMENT_INDEXING_JOB_NAMES.embedDocument]: async (context: JobContext) => {
      return processEmbedDocument(context, deps);
    }
  };
}

async function processChunkDocument(context: JobContext, deps: DocumentIndexingDependencies): Promise<DocumentIndexingJobResult> {
  const { repository, logger } = deps;
  const job = await repository.findJob(context.id, context.data.documentId, DOCUMENT_INDEXING_JOB_NAMES.chunkDocument);

  if (!job) {
    logger.warn("index job record not found", {
      queueJobId: context.id,
      documentId: context.data.documentId,
      jobType: DOCUMENT_INDEXING_JOB_NAMES.chunkDocument
    });
    return {
      documentId: context.data.documentId,
      status: "skipped",
      reason: "job record not found"
    };
  }

  const document = await repository.findDocument(context.data.documentId);
  if (!document) {
    await repository.markJobFailed(job.id, "document not found", null, context.attemptsMade >= 2);
    return {
      documentId: context.data.documentId,
      status: "failed",
      reason: "document not found"
    };
  }

  if (document.parseStatus !== "success") {
    const message = "document must be successfully parsed before indexing";
    await repository.markDocumentIndexFailed(document.id, message, "index_chunk_failed", document.indexVersion ?? null);
    await repository.markJobFailed(job.id, message, "index_chunk_failed", context.attemptsMade >= 2);
    await repository.createProcessingEvent({
      documentId: document.id,
      eventType: "index_chunk_failed",
      stage: "index",
      status: "failed",
      diagnosisCode: "index_chunk_failed",
      summary: "文档尚未完成解析，无法进入索引切分。",
      payload: {
        parse_status: document.parseStatus
      }
    });
    return {
      documentId: document.id,
      status: "failed",
      reason: message
    };
  }

  let embedJobId: string | null = null;
  let embedJobPersisted = false;
  let embedJobEnqueued = false;
  let indexVersion: string | null = document.indexVersion;
  try {
    const resolvedIndexVersion = resolveIndexVersion(deps);
    indexVersion = resolvedIndexVersion;
    const sourceText = pickIndexSourceText(document);
    const chunks = chunkDocumentText({
      documentId: document.id,
      title: document.title,
      text: sourceText,
      strategyVersion: resolvedIndexVersion
    });

    if (chunks.length === 0) {
      throw new Error("document has no indexable content");
    }

    await repository.withTransaction(async (db) => {
      await repository.markJobRunning(job.id, context.id, db);
      await repository.markDocumentIndexChunking(document.id, resolvedIndexVersion, db);
      await repository.createProcessingEvent(
        {
          documentId: document.id,
          eventType: "chunk_started",
          stage: "index",
          status: "processing",
          summary: "开始切分文档内容。",
          payload: {
            index_version: resolvedIndexVersion
          }
        },
        db
      );
      await repository.deleteDocumentChunks(document.id, db);
      await repository.insertDocumentChunks(document.id, chunks, db);
      const nextAttempt = await repository.getNextAttempt(document.id, db);
      const embedJob = await repository.createJob(
        {
          id: randomUUID(),
          documentId: document.id,
          jobType: DOCUMENT_INDEXING_JOB_NAMES.embedDocument,
          status: "queued",
          attempt: nextAttempt
        },
        db
      );
      embedJobId = embedJob.id;
      await repository.createProcessingEvent(
        {
          documentId: document.id,
          eventType: "chunk_succeeded",
          stage: "index",
          status: "success",
          summary: "文档切分完成，等待 embedding。",
          payload: {
            index_version: resolvedIndexVersion,
            chunk_count: chunks.length,
            embed_job_id: embedJob.id
          }
        },
        db
      );
    });
    embedJobPersisted = Boolean(embedJobId);

    if (!embedJobId) {
      throw new Error("failed to create embed job");
    }

    const queueJobId = await deps.enqueueEmbedDocument(document.id, embedJobId);
    embedJobEnqueued = true;
    await repository.updateJobQueueId(embedJobId, queueJobId);
    await repository.markDocumentIndexEmbedding(document.id, resolvedIndexVersion);
    await repository.markJobCompleted(job.id);

    return {
      documentId: document.id,
      status: "success"
    };
  } catch (error) {
    const message = error instanceof Error ? error.message : "failed to enqueue embed job";
    const diagnosisCode: DiagnosisCode = embedJobPersisted && !embedJobEnqueued ? "queue_backlog" : "index_chunk_failed";
    await repository.markDocumentIndexFailed(document.id, message, diagnosisCode, indexVersion);
    await repository.markJobFailed(job.id, message, diagnosisCode, context.attemptsMade >= 2);
    if (embedJobPersisted && embedJobId && !embedJobEnqueued) {
      await repository.markJobFailed(embedJobId, message, diagnosisCode, context.attemptsMade >= 2);
    }
    await repository.createProcessingEvent({
      documentId: document.id,
      eventType: "index_chunk_failed",
      stage: "index",
      status: "failed",
      diagnosisCode,
      summary: diagnosisCode === "queue_backlog" ? "embedding 任务入队失败。" : "文档切分失败。",
      payload: {
        index_version: indexVersion,
        embed_job_id: embedJobId
      }
    });
    return {
      documentId: document.id,
      status: "failed",
      reason: message
    };
  }
}

async function processEmbedDocument(context: JobContext, deps: DocumentIndexingDependencies): Promise<DocumentIndexingJobResult> {
  const { repository, logger } = deps;
  const job = await repository.findJob(context.id, context.data.documentId, DOCUMENT_INDEXING_JOB_NAMES.embedDocument);

  if (!job) {
    logger.warn("index job record not found", {
      queueJobId: context.id,
      documentId: context.data.documentId,
      jobType: DOCUMENT_INDEXING_JOB_NAMES.embedDocument
    });
    return {
      documentId: context.data.documentId,
      status: "skipped",
      reason: "job record not found"
    };
  }

  const document = await repository.findDocument(context.data.documentId);
  if (!document) {
    await repository.markJobFailed(job.id, "document not found", null, context.attemptsMade >= 2);
    return {
      documentId: context.data.documentId,
      status: "failed",
      reason: "document not found"
    };
  }

  const chunks = await repository.listDocumentChunks(document.id);
  if (chunks.length === 0) {
    const message = "no document chunks available for embedding";
    await repository.markDocumentIndexFailed(document.id, message, "index_embedding_failed", document.indexVersion ?? null);
    await repository.markJobFailed(job.id, message, "index_embedding_failed", context.attemptsMade >= 2);
    await repository.createProcessingEvent({
      documentId: document.id,
      eventType: "index_embedding_failed",
      stage: "index",
      status: "failed",
      diagnosisCode: "index_embedding_failed",
      summary: "没有可用的 chunk，无法执行 embedding。",
      payload: null
    });
    return {
      documentId: document.id,
      status: "failed",
      reason: message
    };
  }

  await repository.markJobRunning(job.id, context.id);
  let indexVersion: string | null = document.indexVersion;

  try {
    const resolvedIndexVersion = resolveIndexVersion(deps);
    indexVersion = resolvedIndexVersion;
    const provider = deps.getEmbeddingProvider();
    await repository.markDocumentIndexEmbedding(document.id, resolvedIndexVersion);
    await repository.createProcessingEvent({
      documentId: document.id,
      eventType: "embedding_started",
      stage: "index",
      status: "processing",
      summary: "开始为 chunk 生成 embedding。",
      payload: {
        index_version: resolvedIndexVersion,
        chunk_count: chunks.length
      }
    });
    const embeddings = await embedChunks(provider, chunks.map((chunk) => chunk.contentText));

    await repository.withTransaction(async (db) => {
      for (let index = 0; index < chunks.length; index += 1) {
        const chunk = chunks[index];
        const embedding = embeddings[index];
        if (!embedding) {
          throw new Error("embedding provider returned mismatched vector count");
        }

        await repository.updateChunkEmbedding(chunk.id, embedding, db);
      }

      await repository.markDocumentIndexReady(document.id, resolvedIndexVersion, db);
      await repository.createProcessingEvent(
        {
          documentId: document.id,
          eventType: "index_ready",
          stage: "index",
          status: "success",
          summary: "文档索引已完成。",
          payload: {
            index_version: resolvedIndexVersion,
            chunk_count: chunks.length
          }
        },
        db
      );
      await repository.markJobCompleted(job.id, db);
    });

    return {
      documentId: document.id,
      status: "success"
    };
  } catch (error) {
    const message = error instanceof Error ? error.message : "embedding failed";
    const diagnosisCode: DiagnosisCode = mapEmbeddingFailureCode(message);
    await repository.markDocumentIndexFailed(document.id, message, diagnosisCode, indexVersion);
    await repository.markJobFailed(job.id, message, diagnosisCode, context.attemptsMade >= 2);
    await repository.createProcessingEvent({
      documentId: document.id,
      eventType: "index_embedding_failed",
      stage: "index",
      status: "failed",
      diagnosisCode,
      summary: "文档 embedding 失败。",
      payload: {
        index_version: indexVersion
      }
    });
    return {
      documentId: document.id,
      status: "failed",
      reason: message
    };
  }
}

function resolveIndexVersion(deps: DocumentIndexingDependencies): string {
  const model = deps.embeddingModel?.trim();
  if (!model) {
    throw new Error("embedding model is not configured");
  }

  return buildIndexVersion(model, INDEX_EMBEDDING_DIMENSIONS);
}

function pickIndexSourceText(document: IndexingDocumentRecord): string {
  return (document.contentRaw ?? document.contentClean ?? "").trim();
}

async function embedChunks(provider: EmbeddingProvider, chunks: string[]): Promise<number[][]> {
  const embeddings: number[][] = [];

  for (let index = 0; index < chunks.length; index += INDEX_BATCH_SIZE) {
    const batch = chunks.slice(index, index + INDEX_BATCH_SIZE);
    const result = await provider.embed(batch);
    embeddings.push(...result.vectors);
  }

  return embeddings;
}

function mapEmbeddingFailureCode(message: string): DiagnosisCode {
  const normalized = message.toLowerCase();
  if (normalized.includes("timeout")) {
    return "provider_timeout";
  }

  return "index_embedding_failed";
}
