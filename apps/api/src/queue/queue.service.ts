import { Injectable, OnApplicationShutdown } from "@nestjs/common";
import { Queue } from "bullmq";
import IORedis from "ioredis";
import { loadApiEnv } from "../config/env";
import {
  ANSWER_ORCHESTRATION_JOB_NAMES,
  ANSWER_ORCHESTRATION_QUEUE_NAME,
  DOCUMENT_INDEXING_JOB_NAMES,
  DOCUMENT_INDEXING_QUEUE_NAME,
  DOCUMENT_PROCESSING_JOB_NAMES,
  DOCUMENT_PROCESSING_QUEUE_NAME,
  type AnswerOrchestrationJobName,
  type DocumentIndexingJobName,
  type DocumentProcessingJobName
} from "./queue.constants";

interface EnqueueDocumentJobParams {
  name: DocumentProcessingJobName;
  documentId: string;
  uploadId?: string;
}

interface EnqueueIndexingJobParams {
  name: DocumentIndexingJobName;
  documentId: string;
  jobId: string;
}

interface EnqueueAnswerJobParams {
  name: AnswerOrchestrationJobName;
  sessionId: string;
}

@Injectable()
export class QueueService implements OnApplicationShutdown {
  private readonly env = loadApiEnv();
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

  constructor() {
    this.connection.on("error", (error) => {
      if (this.isIgnorableConnectionError(error)) {
        return;
      }

      console.error("[QueueService] Redis connection error", error);
    });

    this.registerQueueErrorHandler(this.documentProcessingQueue, "document-processing");
    this.registerQueueErrorHandler(this.documentIndexingQueue, "document-indexing");
    this.registerQueueErrorHandler(this.answerOrchestrationQueue, "answer-orchestration");
  }

  async enqueueDocumentJob(params: EnqueueDocumentJobParams): Promise<string> {
    const job = await this.documentProcessingQueue.add(
      params.name,
      {
        documentId: params.documentId,
        uploadId: params.uploadId
      },
      this.getDefaultJobOptions()
    );

    return job.id ?? "";
  }

  async enqueueIndexingJob(params: EnqueueIndexingJobParams): Promise<string> {
    const job = await this.documentIndexingQueue.add(
      params.name,
      {
        documentId: params.documentId,
        jobId: params.jobId
      },
      this.getDefaultJobOptions()
    );

    return job.id ?? "";
  }

  async enqueueAnswerJob(params: EnqueueAnswerJobParams): Promise<string> {
    const job = await this.answerOrchestrationQueue.add(
      params.name,
      {
        sessionId: params.sessionId
      },
      this.getDefaultJobOptions()
    );

    return job.id ?? "";
  }

  async enqueueParseDocument(documentId: string, uploadId?: string): Promise<string> {
    return this.enqueueDocumentJob({
      name: DOCUMENT_PROCESSING_JOB_NAMES.parseDocument,
      documentId,
      uploadId
    });
  }

  async enqueueReparseDocument(documentId: string): Promise<string> {
    return this.enqueueDocumentJob({
      name: DOCUMENT_PROCESSING_JOB_NAMES.reparseDocument,
      documentId
    });
  }

  async enqueueRunOcr(documentId: string, uploadId?: string): Promise<string> {
    return this.enqueueDocumentJob({
      name: DOCUMENT_PROCESSING_JOB_NAMES.runOcr,
      documentId,
      uploadId
    });
  }

  async enqueueFetchLink(documentId: string): Promise<string> {
    return this.enqueueDocumentJob({
      name: DOCUMENT_PROCESSING_JOB_NAMES.fetchLink,
      documentId
    });
  }

  async enqueueRebuildSearchProjection(documentId: string): Promise<string> {
    return this.enqueueDocumentJob({
      name: DOCUMENT_PROCESSING_JOB_NAMES.rebuildSearchProjection,
      documentId
    });
  }

  async enqueueChunkDocument(documentId: string, jobId: string): Promise<string> {
    return this.enqueueIndexingJob({
      name: DOCUMENT_INDEXING_JOB_NAMES.chunkDocument,
      documentId,
      jobId
    });
  }

  async enqueueEmbedDocument(documentId: string, jobId: string): Promise<string> {
    return this.enqueueIndexingJob({
      name: DOCUMENT_INDEXING_JOB_NAMES.embedDocument,
      documentId,
      jobId
    });
  }

  async enqueueAnswerSession(sessionId: string): Promise<string> {
    return this.enqueueAnswerJob({
      name: ANSWER_ORCHESTRATION_JOB_NAMES.answerSession,
      sessionId
    });
  }

  async checkConnection(): Promise<void> {
    const result = await this.connection.ping();
    if (result !== "PONG") {
      throw new Error("Redis ping failed");
    }
  }

  async onApplicationShutdown(): Promise<void> {
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

  private registerQueueErrorHandler(queue: Queue, queueName: string) {
    queue.on("error", (error) => {
      if (this.isIgnorableConnectionError(error)) {
        return;
      }

      console.error(`[QueueService] ${queueName} queue error`, error);
    });
  }

  private isIgnorableConnectionError(error: Error): boolean {
    return error.message === "Connection is closed.";
  }
}
