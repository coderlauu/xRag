import { Injectable, OnApplicationShutdown } from "@nestjs/common";
import { Queue } from "bullmq";
import IORedis from "ioredis";
import { loadApiEnv } from "../config/env";
import {
  DOCUMENT_PROCESSING_JOB_NAMES,
  DOCUMENT_PROCESSING_QUEUE_NAME,
  type DocumentProcessingJobName
} from "./queue.constants";

interface EnqueueDocumentJobParams {
  name: DocumentProcessingJobName;
  documentId: string;
  uploadId?: string;
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

  private readonly queue = new Queue(this.env.documentProcessingQueueName || DOCUMENT_PROCESSING_QUEUE_NAME, {
    connection: this.connection,
    prefix: "xrag"
  });

  constructor() {
    this.connection.on("error", (error) => {
      if (this.isIgnorableConnectionError(error)) {
        return;
      }

      console.error("[QueueService] Redis connection error", error);
    });

    this.queue.on("error", (error) => {
      if (this.isIgnorableConnectionError(error)) {
        return;
      }

      console.error("[QueueService] BullMQ queue error", error);
    });
  }

  async enqueueDocumentJob(params: EnqueueDocumentJobParams): Promise<string> {
    const job = await this.queue.add(
      params.name,
      {
        documentId: params.documentId,
        uploadId: params.uploadId
      },
      {
        attempts: 3,
        backoff: {
          type: "exponential",
          delay: 2_000
        },
        removeOnComplete: {
          count: 1000
        },
        removeOnFail: {
          count: 5000
        }
      }
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

  async checkConnection(): Promise<void> {
    const result = await this.connection.ping();
    if (result !== "PONG") {
      throw new Error("Redis ping failed");
    }
  }

  async onApplicationShutdown(): Promise<void> {
    await this.queue.close();
    await this.connection.quit();
  }

  private isIgnorableConnectionError(error: Error): boolean {
    return error.message === "Connection is closed.";
  }
}
