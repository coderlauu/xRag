import { Pool } from "pg";
import { loadWorkerEnv } from "../config/env";

export function createDatabasePool() {
  const env = loadWorkerEnv();
  return new Pool({
    connectionString: env.databaseUrl,
    max: env.databasePoolMax
  });
}
