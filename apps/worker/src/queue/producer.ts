import { Queue } from "bullmq";
import IORedis from "ioredis";
import { loadWorkerEnv } from "../config/env";
import { DOCUMENT_PROCESSING_JOB_NAMES, DOCUMENT_PROCESSING_QUEUE_NAME } from "./constants";

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

  private readonly queue = new Queue(this.env.documentProcessingQueueName || DOCUMENT_PROCESSING_QUEUE_NAME, {
    connection: this.connection,
    prefix: "xrag"
  });

  async enqueueRunOcr(documentId: string, uploadId?: string): Promise<string> {
    const job = await this.queue.add(
      DOCUMENT_PROCESSING_JOB_NAMES.runOcr,
      {
        documentId,
        uploadId
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

  async close() {
    await this.queue.close();
    await this.connection.quit();
  }
}
