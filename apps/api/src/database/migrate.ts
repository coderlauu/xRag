import fs from "node:fs";
import path from "node:path";
import { migrate } from "drizzle-orm/node-postgres/migrator";
import { DatabaseService } from "./database.service";

function resolveMigrationsFolder() {
  const candidates = [
    path.resolve(process.cwd(), "src/database/migrations"),
    path.resolve(process.cwd(), "apps/api/src/database/migrations")
  ];

  return candidates.find((candidate) => fs.existsSync(candidate)) || candidates[0];
}

async function main() {
  const database = new DatabaseService();

  try {
    await migrate(database.db, {
      migrationsFolder: resolveMigrationsFolder()
    });
    process.stdout.write("Migrations applied successfully\n");
  } finally {
    await database.onApplicationShutdown();
  }
}

void main();
