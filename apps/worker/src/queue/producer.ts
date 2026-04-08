import { Queue } from "bullmq";
import IORedis from "ioredis";
import { loadWorkerEnv } from "../config/env";
import {
  ANSWER_ORCHESTRATION_JOB_NAMES,
  ANSWER_ORCHESTRATION_QUEUE_NAME,
  DOCUMENT_INDEXING_JOB_NAMES,
  DOCUMENT_INDEXING_QUEUE_NAME,
  DOCUMENT_PROCESSING_JOB_NAMES,
  DOCUMENT_PROCESSING_QUEUE_NAME
} from "./constants";

export class WorkerQueueProducer {
  private readonly env = loadWorkerEnv();
  private readonly connection = this.env.redisUrl
    ? new IORedis(this.env.redisUrl, { maxRetriesPerRequest: null })
    : new IORedis({
        host: this.env.redisHost,
        port: this.env.redisPort,
        db: this.env.redisDb,
        maxRetriesPerRequest: null
      });

  private readonly documentProcessingQueue = new Queue(
    this.env.documentProcessingQueueName || DOCUMENT_PROCESSING_QUEUE_NAME,
    {
      connection: this.connection,
      prefix: "xrag"
    }
  );

  private readonly documentIndexingQueue = new Queue(
    this.env.documentIndexingQueueName || DOCUMENT_INDEXING_QUEUE_NAME,
    {
      connection: this.connection,
      prefix: "xrag"
    }
  );

  private readonly answerOrchestrationQueue = new Queue(
    this.env.answerOrchestrationQueueName || ANSWER_ORCHESTRATION_QUEUE_NAME,
    {
      connection: this.connection,
      prefix: "xrag"
    }
  );

  async enqueueRunOcr(documentId: string, uploadId?: string): Promise<string> {
    const job = await this.documentProcessingQueue.add(
      DOCUMENT_PROCESSING_JOB_NAMES.runOcr,
      {
        documentId,
        uploadId
      },
      this.getDefaultJobOptions()
    );

    return job.id ?? "";
  }

  async enqueueChunkDocument(documentId: string, jobId: string): Promise<string> {
    const job = await this.documentIndexingQueue.add(
      DOCUMENT_INDEXING_JOB_NAMES.chunkDocument,
      {
        documentId,
        jobId
      },
      this.getDefaultJobOptions()
    );

    return job.id ?? "";
  }

  async enqueueEmbedDocument(documentId: string, jobId: string): Promise<string> {
    const job = await this.documentIndexingQueue.add(
      DOCUMENT_INDEXING_JOB_NAMES.embedDocument,
      {
        documentId,
        jobId
      },
      this.getDefaultJobOptions()
    );

    return job.id ?? "";
  }

  async enqueueAnswerSession(sessionId: string): Promise<string> {
    const job = await this.answerOrchestrationQueue.add(
      ANSWER_ORCHESTRATION_JOB_NAMES.answerSession,
      {
        sessionId
      },
      this.getDefaultJobOptions()
    );

    return job.id ?? "";
  }

  async close() {
    await this.documentProcessingQueue.close();
    await this.documentIndexingQueue.close();
    await this.answerOrchestrationQueue.close();
    await this.connection.quit();
  }

  private getDefaultJobOptions() {
    return {
      attempts: 3,
      backoff: {
        type: "exponential" as const,
        delay: 2_000
      },
      removeOnComplete: {
        count: 1000
      },
      removeOnFail: {
        count: 5000
      }
    };
  }
}
