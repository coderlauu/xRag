import { QueueEvents, Worker } from "bullmq";
import { createLogger } from "../logging/logger";
import { loadWorkerEnv } from "../config/env";
import { createDatabasePool } from "../database/database";
import { WorkerRepository } from "../database/repository";
import {
  DOCUMENT_PROCESSING_JOB_NAMES,
  DOCUMENT_PROCESSING_QUEUE_NAME,
  type DocumentProcessingJobName
} from "../queue/constants";
import { WorkerQueueProducer } from "../queue/producer";
import { createDocumentProcessingHandlers, type DocumentProcessingJobData } from "../jobs/document-processing";
import { WorkerStorageService } from "../storage/storage";
import { fetchAndExtractLinkDocument } from "../jobs/link-parser";

function createRedisConnection(env: ReturnType<typeof loadWorkerEnv>) {
  if (env.redisUrl) {
    return {
      connection: {
        url: env.redisUrl
      }
    };
  }

  return {
    connection: {
      host: env.redisHost,
      port: env.redisPort,
      db: env.redisDb
    }
  };
}

function isDocumentProcessingJobName(name: string): name is DocumentProcessingJobName {
  return Object.values(DOCUMENT_PROCESSING_JOB_NAMES).includes(name as DocumentProcessingJobName);
}

export async function bootstrapWorker() {
  const env = loadWorkerEnv();
  const logger = createLogger(env.logLevel);
  const redisConnection = createRedisConnection(env);
  const databasePool = createDatabasePool();
  const repository = new WorkerRepository(databasePool);
  const storage = new WorkerStorageService();
  const queueProducer = new WorkerQueueProducer();
  const documentProcessingHandlers = createDocumentProcessingHandlers({
    repository,
    storage,
    logger,
    enqueueOcr: (documentId, uploadId) => queueProducer.enqueueRunOcr(documentId, uploadId),
    fetchLink: (url) =>
      fetchAndExtractLinkDocument(url, fetch, {
        timeoutMs: env.linkFetchTimeoutMs,
        retryCount: env.linkFetchRetryCount,
        retryBackoffMs: env.linkFetchRetryBackoffMs
      })
  });

  const worker = new Worker<DocumentProcessingJobData>(
    env.queueName,
    async (job) => {
      if (!isDocumentProcessingJobName(job.name)) {
        logger.warn("unknown job name received", {
          queueName: env.queueName,
          jobId: job.id,
          jobName: job.name
        });

        return {
          documentId: job.data.documentId,
          status: "skipped" as const,
          reason: `unsupported job name: ${job.name}`
        };
      }

      const handler = documentProcessingHandlers[job.name];
      return handler(
        {
          name: job.name,
          id: job.id ?? "unknown",
          data: job.data,
          attemptsMade: job.attemptsMade
        }
      );
    },
    {
      ...redisConnection,
      concurrency: env.concurrency,
      prefix: "xrag",
      removeOnComplete: {
        count: 1000
      },
      removeOnFail: {
        count: 5000
      }
    }
  );

  const queueEvents = new QueueEvents(env.queueName, {
    ...redisConnection
  });

  worker.on("completed", (job, result) => {
    logger.info("job completed", {
      queueName: env.queueName,
      workerName: env.workerName,
      jobId: job.id,
      jobName: job.name,
      result
    });
  });

  worker.on("failed", (job, error) => {
    logger.error("job failed", {
      queueName: env.queueName,
      workerName: env.workerName,
      jobId: job?.id,
      jobName: job?.name,
      errorMessage: error.message
    });
  });

  queueEvents.on("failed", ({ jobId, failedReason }) => {
    logger.error("queue event failed", {
      queueName: DOCUMENT_PROCESSING_QUEUE_NAME,
      jobId,
      failedReason
    });
  });

  logger.info("worker booted", {
    workerName: env.workerName,
    queueName: env.queueName,
    concurrency: env.concurrency,
    redis: env.redisUrl ? "url" : `${env.redisHost}:${env.redisPort}/${env.redisDb}`
  });

  const shutdown = async () => {
    await queueEvents.close();
    await worker.close();
    await databasePool.end();
    await queueProducer.close();
    storage.destroy();
  };

  process.once("SIGINT", () => {
    void shutdown();
  });
  process.once("SIGTERM", () => {
    void shutdown();
  });
}
