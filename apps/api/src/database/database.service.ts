import { Injectable, OnApplicationShutdown } from "@nestjs/common";
import { drizzle, type NodePgDatabase } from "drizzle-orm/node-postgres";
import { Pool } from "pg";
import { loadApiEnv } from "../config/env";
import * as schema from "./schema";

export type DatabaseSchema = typeof schema;
export type DatabaseClient = NodePgDatabase<DatabaseSchema>;

@Injectable()
export class DatabaseService implements OnApplicationShutdown {
  private readonly env = loadApiEnv();
  private readonly pool = new Pool({
    connectionString: this.env.databaseUrl,
    max: this.env.databasePoolMax
  });

  readonly db: DatabaseClient = drizzle(this.pool, { schema });

  async checkConnection(): Promise<void> {
    await this.pool.query("select 1");
  }

  async onApplicationShutdown(): Promise<void> {
    await this.pool.end();
  }
}
