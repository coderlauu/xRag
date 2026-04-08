import { QueueEvents, type Job, Worker } from "bullmq";
import { createLogger, type Logger } from "../logging/logger";
import { loadWorkerEnv, type WorkerEnv } from "../config/env";
import { createDatabasePool } from "../database/database";
import { WorkerRepository } from "../database/repository";
import {
  ANSWER_ORCHESTRATION_JOB_NAMES,
  ANSWER_ORCHESTRATION_QUEUE_NAME,
  DOCUMENT_INDEXING_JOB_NAMES,
  DOCUMENT_INDEXING_QUEUE_NAME,
  DOCUMENT_PROCESSING_JOB_NAMES,
  DOCUMENT_PROCESSING_QUEUE_NAME,
  type AnswerOrchestrationJobData,
  type AnswerOrchestrationJobName,
  type DocumentIndexingJobData,
  type DocumentIndexingJobName,
  type DocumentProcessingJobName
} from "../queue/constants";
import { WorkerQueueProducer } from "../queue/producer";
import { createDocumentProcessingHandlers, type DocumentProcessingJobData } from "../jobs/document-processing";
import { WorkerStorageService } from "../storage/storage";
import { fetchAndExtractLinkDocument } from "../jobs/link-parser";

type QueueRuntime = {
  worker: Worker;
  queueEvents: QueueEvents;
  queueName: string;
};

function createRedisConnection(env: WorkerEnv) {
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

function isDocumentIndexingJobName(name: string): name is DocumentIndexingJobName {
  return Object.values(DOCUMENT_INDEXING_JOB_NAMES).includes(name as DocumentIndexingJobName);
}

function isAnswerOrchestrationJobName(name: string): name is AnswerOrchestrationJobName {
  return Object.values(ANSWER_ORCHESTRATION_JOB_NAMES).includes(name as AnswerOrchestrationJobName);
}

function createQueueRuntime<TData>(
  env: WorkerEnv,
  logger: Logger,
  queueName: string,
  concurrency: number,
  processor: (job: Job<TData>) => Promise<unknown>
): QueueRuntime {
  const redisConnection = createRedisConnection(env);
  const worker = new Worker<TData>(queueName, processor, {
    ...redisConnection,
    concurrency,
    prefix: "xrag",
    removeOnComplete: {
      count: 1000
    },
    removeOnFail: {
      count: 5000
    }
  });

  const queueEvents = new QueueEvents(queueName, {
    ...redisConnection
  });

  worker.on("completed", (job, result) => {
    logger.info("job completed", {
      queueName,
      workerName: env.workerName,
      jobId: job.id,
      jobName: job.name,
      result
    });
  });

  worker.on("failed", (job, error) => {
    logger.error("job failed", {
      queueName,
      workerName: env.workerName,
      jobId: job?.id,
      jobName: job?.name,
      errorMessage: error.message
    });
  });

  queueEvents.on("failed", ({ jobId, failedReason }) => {
    logger.error("queue event failed", {
      queueName,
      jobId,
      failedReason
    });
  });

  return {
    worker,
    queueEvents,
    queueName
  };
}

export async function bootstrapWorker() {
  const env = loadWorkerEnv();
  const logger = createLogger(env.logLevel);
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

  const runtimes: QueueRuntime[] = [
    createQueueRuntime<DocumentProcessingJobData>(
      env,
      logger,
      env.documentProcessingQueueName || DOCUMENT_PROCESSING_QUEUE_NAME,
      env.documentProcessingConcurrency,
      async (job) => {
        if (!isDocumentProcessingJobName(job.name)) {
          logger.warn("unknown job name received", {
            queueName: env.documentProcessingQueueName,
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
        return handler({
          name: job.name,
          id: job.id ?? "unknown",
          data: job.data,
          attemptsMade: job.attemptsMade
        });
      }
    ),
    createQueueRuntime<DocumentIndexingJobData>(
      env,
      logger,
      env.documentIndexingQueueName || DOCUMENT_INDEXING_QUEUE_NAME,
      env.documentIndexingConcurrency,
      async (job) => {
        if (!isDocumentIndexingJobName(job.name)) {
          logger.warn("unknown job name received", {
            queueName: env.documentIndexingQueueName,
            jobId: job.id,
            jobName: job.name
          });

          return {
            documentId: job.data.documentId,
            status: "skipped" as const,
            reason: `unsupported job name: ${job.name}`
          };
        }

        logger.warn("document indexing queue received job before lane implementation", {
          queueName: env.documentIndexingQueueName,
          jobId: job.id,
          jobName: job.name,
          documentId: job.data.documentId,
          jobIdRef: job.data.jobId
        });

        return {
          documentId: job.data.documentId,
          status: "skipped" as const,
          reason: "document indexing lane not implemented yet"
        };
      }
    ),
    createQueueRuntime<AnswerOrchestrationJobData>(
      env,
      logger,
      env.answerOrchestrationQueueName || ANSWER_ORCHESTRATION_QUEUE_NAME,
      env.answerOrchestrationConcurrency,
      async (job) => {
        if (!isAnswerOrchestrationJobName(job.name)) {
          logger.warn("unknown job name received", {
            queueName: env.answerOrchestrationQueueName,
            jobId: job.id,
            jobName: job.name
          });

          return {
            sessionId: job.data.sessionId,
            status: "skipped" as const,
            reason: `unsupported job name: ${job.name}`
          };
        }

        logger.warn("answer orchestration queue received job before lane implementation", {
          queueName: env.answerOrchestrationQueueName,
          jobId: job.id,
          jobName: job.name,
          sessionId: job.data.sessionId
        });

        return {
          sessionId: job.data.sessionId,
          status: "skipped" as const,
          reason: "answer orchestration lane not implemented yet"
        };
      }
    )
  ];

  logger.info("worker booted", {
    workerName: env.workerName,
    queues: runtimes.map((runtime) => runtime.queueName),
    concurrency: {
      documentProcessing: env.documentProcessingConcurrency,
      documentIndexing: env.documentIndexingConcurrency,
      answerOrchestration: env.answerOrchestrationConcurrency
    },
    redis: env.redisUrl ? "url" : `${env.redisHost}:${env.redisPort}/${env.redisDb}`
  });

  let shuttingDown = false;
  const shutdown = async () => {
    if (shuttingDown) {
      return;
    }

    shuttingDown = true;
    await Promise.all(runtimes.map(async (runtime) => runtime.queueEvents.close()));
    await Promise.all(runtimes.map(async (runtime) => runtime.worker.close()));
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
