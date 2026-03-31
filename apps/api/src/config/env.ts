export interface ApiEnv {
  port: number;
  databaseUrl: string;
  databasePoolMax: number;
  uploadUrlBase: string;
  uploadUrlExpiresIn: number;
}

function parseInteger(value: string | undefined, fallback: number): number {
  if (!value) {
    return fallback;
  }

  const parsed = Number.parseInt(value, 10);
  return Number.isNaN(parsed) ? fallback : parsed;
}

export function loadApiEnv(env: NodeJS.ProcessEnv = process.env): ApiEnv {
  return {
    port: parseInteger(env.PORT, 3001),
    databaseUrl: env.DATABASE_URL || "postgresql://xrag:xrag@127.0.0.1:5432/xrag",
    databasePoolMax: parseInteger(env.DATABASE_POOL_MAX, 10),
    uploadUrlBase: env.UPLOAD_URL_BASE || "https://storage.example.com",
    uploadUrlExpiresIn: parseInteger(env.UPLOAD_URL_EXPIRES_IN, 900)
  };
}
