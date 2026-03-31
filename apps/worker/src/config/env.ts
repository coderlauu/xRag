export interface WorkerEnv {
  queueName: string;
  workerName: string;
  concurrency: number;
  logLevel: "debug" | "info" | "warn" | "error";
  redisUrl?: string;
  redisHost: string;
  redisPort: number;
  redisDb: number;
}

function parseInteger(value: string | undefined, fallback: number): number {
  if (!value) return fallback;
  const parsed = Number.parseInt(value, 10);
  return Number.isNaN(parsed) ? fallback : parsed;
}

function parseLogLevel(value: string | undefined): WorkerEnv["logLevel"] {
  switch (value) {
    case "debug":
    case "warn":
    case "error":
      return value;
    case "info":
    default:
      return "info";
  }
}

export function loadWorkerEnv(env: NodeJS.ProcessEnv = process.env): WorkerEnv {
  return {
    queueName: env.WORKER_QUEUE_NAME || "document-processing",
    workerName: env.WORKER_NAME || "xrag-document-worker",
    concurrency: parseInteger(env.WORKER_CONCURRENCY, 1),
    logLevel: parseLogLevel(env.LOG_LEVEL),
    redisUrl: env.REDIS_URL || undefined,
    redisHost: env.REDIS_HOST || "127.0.0.1",
    redisPort: parseInteger(env.REDIS_PORT, 6379),
    redisDb: parseInteger(env.REDIS_DB, 0)
  };
}
