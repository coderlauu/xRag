export interface ApiEnv {
  port: number;
  databaseUrl: string;
  databasePoolMax: number;
  uploadUrlExpiresIn: number;
  redisUrl?: string;
  redisHost: string;
  redisPort: number;
  redisDb: number;
  queueName: string;
  storageEndpoint: string;
  storagePublicUrl: string;
  storageRegion: string;
  storageBucket: string;
  storageAccessKeyId: string;
  storageSecretAccessKey: string;
  storageForcePathStyle: boolean;
}

function parseInteger(value: string | undefined, fallback: number): number {
  if (!value) {
    return fallback;
  }

  const parsed = Number.parseInt(value, 10);
  return Number.isNaN(parsed) ? fallback : parsed;
}

function parseBoolean(value: string | undefined, fallback: boolean): boolean {
  if (!value) {
    return fallback;
  }

  return ["1", "true", "yes", "on"].includes(value.toLowerCase());
}

export function loadApiEnv(env: NodeJS.ProcessEnv = process.env): ApiEnv {
  return {
    port: parseInteger(env.PORT, 3001),
    databaseUrl: env.DATABASE_URL || "postgresql://xrag:xrag@127.0.0.1:5432/xrag",
    databasePoolMax: parseInteger(env.DATABASE_POOL_MAX, 10),
    uploadUrlExpiresIn: parseInteger(env.UPLOAD_URL_EXPIRES_IN, 900),
    redisUrl: env.REDIS_URL || undefined,
    redisHost: env.REDIS_HOST || "127.0.0.1",
    redisPort: parseInteger(env.REDIS_PORT, 6379),
    redisDb: parseInteger(env.REDIS_DB, 0),
    queueName: env.WORKER_QUEUE_NAME || "document-processing",
    storageEndpoint: env.STORAGE_ENDPOINT || "http://127.0.0.1:9000",
    storagePublicUrl: env.STORAGE_PUBLIC_URL || env.STORAGE_ENDPOINT || "http://127.0.0.1:9000",
    storageRegion: env.STORAGE_REGION || "us-east-1",
    storageBucket: env.STORAGE_BUCKET || "xrag",
    storageAccessKeyId: env.STORAGE_ACCESS_KEY_ID || "xrag",
    storageSecretAccessKey: env.STORAGE_SECRET_ACCESS_KEY || "xragminio",
    storageForcePathStyle: parseBoolean(env.STORAGE_FORCE_PATH_STYLE, true)
  };
}
