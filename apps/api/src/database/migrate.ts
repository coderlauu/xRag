import fs from "node:fs";
import path from "node:path";
import { migrate } from "drizzle-orm/node-postgres/migrator";
import { Client } from "pg";
import { loadApiEnv } from "../config/env";
import { DatabaseService } from "./database.service";

function resolveMigrationsFolder() {
  const candidates = [
    path.resolve(process.cwd(), "src/database/migrations"),
    path.resolve(process.cwd(), "apps/api/src/database/migrations")
  ];

  return candidates.find((candidate) => fs.existsSync(candidate)) || candidates[0];
}

function quoteIdentifier(identifier: string) {
  return `"${identifier.replace(/"/g, "\"\"")}"`;
}

async function ensureDatabaseExists(connectionString: string) {
  const targetUrl = new URL(connectionString);
  const databaseName = targetUrl.pathname.replace(/^\//, "");

  if (!databaseName || databaseName === "template1") {
    return;
  }

  const adminUrl = new URL(connectionString);
  adminUrl.pathname = "/template1";

  const adminClient = new Client({
    connectionString: adminUrl.toString()
  });

  try {
    await adminClient.connect();

    const existing = await adminClient.query<{ present: number }>(
      "select 1 as present from pg_database where datname = $1",
      [databaseName]
    );

    if (existing.rowCount && existing.rows[0]?.present === 1) {
      return;
    }

    const ownerName = targetUrl.username;
    const createSql = ownerName
      ? `create database ${quoteIdentifier(databaseName)} owner ${quoteIdentifier(ownerName)}`
      : `create database ${quoteIdentifier(databaseName)}`;

    await adminClient.query(createSql);
    process.stdout.write(`Database ${databaseName} created successfully\n`);
  } finally {
    await adminClient.end();
  }
}

async function main() {
  const env = loadApiEnv();
  const databaseService = new DatabaseService();

  try {
    await ensureDatabaseExists(env.databaseUrl);

    await migrate(databaseService.db, {
      migrationsFolder: resolveMigrationsFolder()
    });
    process.stdout.write("Migrations applied successfully\n");
  } finally {
    await databaseService.onApplicationShutdown();
  }
}

void main();
