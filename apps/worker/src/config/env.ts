export interface WorkerEnv {
  documentProcessingQueueName: string;
  documentIndexingQueueName: string;
  answerOrchestrationQueueName: string;
  workerName: string;
  documentProcessingConcurrency: number;
  documentIndexingConcurrency: number;
  answerOrchestrationConcurrency: number;
  logLevel: "debug" | "info" | "warn" | "error";
  redisUrl?: string;
  redisHost: string;
  redisPort: number;
  redisDb: number;
  databaseUrl: string;
  databasePoolMax: number;
  storageEndpoint: string;
  storageRegion: string;
  storageBucket: string;
  storageAccessKeyId: string;
  storageSecretAccessKey: string;
  storageForcePathStyle: boolean;
  ocrLanguage: string;
  ocrTimeoutMs: number;
  linkFetchTimeoutMs: number;
  linkFetchRetryCount: number;
  linkFetchRetryBackoffMs: number;
  embeddingProviderBaseUrl?: string;
  embeddingProviderApiKey?: string;
  embeddingModel?: string;
  embeddingTimeoutMs: number;
  answerProviderBaseUrl?: string;
  answerProviderApiKey?: string;
  answerModel?: string;
  answerTimeoutMs: number;
  aiProviderMaxRetries: number;
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

function parseBoolean(value: string | undefined, fallback: boolean): boolean {
  if (!value) return fallback;
  return ["1", "true", "yes", "on"].includes(value.toLowerCase());
}

function parseOptionalString(value: string | undefined): string | undefined {
  if (!value) return undefined;

  const normalized = value.trim();
  return normalized.length > 0 ? normalized : undefined;
}

export function loadWorkerEnv(env: NodeJS.ProcessEnv = process.env): WorkerEnv {
  return {
    documentProcessingQueueName:
      env.DOCUMENT_PROCESSING_QUEUE_NAME || env.WORKER_QUEUE_NAME || "document-processing",
    documentIndexingQueueName: env.DOCUMENT_INDEXING_QUEUE_NAME || "document-indexing",
    answerOrchestrationQueueName: env.ANSWER_ORCHESTRATION_QUEUE_NAME || "answer-orchestration",
    workerName: env.WORKER_NAME || "xrag-document-worker",
    documentProcessingConcurrency: parseInteger(
      env.DOCUMENT_PROCESSING_CONCURRENCY || env.WORKER_CONCURRENCY,
      1
    ),
    documentIndexingConcurrency: parseInteger(env.DOCUMENT_INDEXING_CONCURRENCY, 1),
    answerOrchestrationConcurrency: parseInteger(env.ANSWER_ORCHESTRATION_CONCURRENCY, 1),
    logLevel: parseLogLevel(env.LOG_LEVEL),
    redisUrl: env.REDIS_URL || undefined,
    redisHost: env.REDIS_HOST || "127.0.0.1",
    redisPort: parseInteger(env.REDIS_PORT, 6379),
    redisDb: parseInteger(env.REDIS_DB, 0),
    databaseUrl: env.DATABASE_URL || "postgresql://xrag:xrag@127.0.0.1:5432/xrag",
    databasePoolMax: parseInteger(env.DATABASE_POOL_MAX, 10),
    storageEndpoint: env.STORAGE_ENDPOINT || "http://127.0.0.1:9000",
    storageRegion: env.STORAGE_REGION || "us-east-1",
    storageBucket: env.STORAGE_BUCKET || "xrag",
    storageAccessKeyId: env.STORAGE_ACCESS_KEY_ID || "xrag",
    storageSecretAccessKey: env.STORAGE_SECRET_ACCESS_KEY || "xragminio",
    storageForcePathStyle: parseBoolean(env.STORAGE_FORCE_PATH_STYLE, true),
    ocrLanguage: env.OCR_LANGUAGE || "chi_sim+eng",
    ocrTimeoutMs: parseInteger(env.OCR_TIMEOUT_MS, 30000),
    linkFetchTimeoutMs: parseInteger(env.LINK_FETCH_TIMEOUT_MS, 20000),
    linkFetchRetryCount: parseInteger(env.LINK_FETCH_RETRY_COUNT, 2),
    linkFetchRetryBackoffMs: parseInteger(env.LINK_FETCH_RETRY_BACKOFF_MS, 1500),
    embeddingProviderBaseUrl: parseOptionalString(env.EMBEDDING_PROVIDER_BASE_URL),
    embeddingProviderApiKey: parseOptionalString(env.EMBEDDING_PROVIDER_API_KEY),
    embeddingModel: parseOptionalString(env.EMBEDDING_MODEL),
    embeddingTimeoutMs: parseInteger(env.EMBEDDING_TIMEOUT_MS, 30000),
    answerProviderBaseUrl: parseOptionalString(env.ANSWER_PROVIDER_BASE_URL),
    answerProviderApiKey: parseOptionalString(env.ANSWER_PROVIDER_API_KEY),
    answerModel: parseOptionalString(env.ANSWER_MODEL),
    answerTimeoutMs: parseInteger(env.ANSWER_TIMEOUT_MS, 60000),
    aiProviderMaxRetries: parseInteger(env.AI_PROVIDER_MAX_RETRIES, 2)
  };
}
