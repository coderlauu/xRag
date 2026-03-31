import { QueueEvents, Worker } from "bullmq";

const connection = {
  host: process.env.REDIS_HOST || "127.0.0.1",
  port: Number(process.env.REDIS_PORT || 6379)
};

const queueName = "document-processing";

const worker = new Worker(
  queueName,
  async (job) => {
    console.log("[worker] received job", job.id, job.name);
  },
  { connection }
);

const queueEvents = new QueueEvents(queueName, { connection });

worker.on("completed", (job) => {
  console.log("[worker] completed", job.id);
});

queueEvents.on("failed", ({ jobId, failedReason }) => {
  console.error("[worker] failed", jobId, failedReason);
});

console.log(`[worker] listening on queue: ${queueName}`);
