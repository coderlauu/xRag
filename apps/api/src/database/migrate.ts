import path from "node:path";
import { migrate } from "drizzle-orm/node-postgres/migrator";
import { DatabaseService } from "./database.service";

async function main() {
  const database = new DatabaseService();

  try {
    await migrate(database.db, {
      migrationsFolder: path.resolve(process.cwd(), "src/database/migrations")
    });
    process.stdout.write("Migrations applied successfully\n");
  } finally {
    await database.onApplicationShutdown();
  }
}

void main();
