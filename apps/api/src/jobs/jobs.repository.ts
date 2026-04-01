import { Injectable } from "@nestjs/common";
import { desc, eq } from "drizzle-orm";
import { DatabaseService, type DatabaseClient } from "../database/database.service";
import { documentParseJobs } from "../database/schema";

type DatabaseExecutor = Pick<DatabaseClient, "select" | "insert" | "update">;

@Injectable()
export class JobsRepository {
  constructor(private readonly database: DatabaseService) {}

  async getJobById(jobId: string, db: DatabaseExecutor = this.database.db) {
    const [job] = await db.select().from(documentParseJobs).where(eq(documentParseJobs.id, jobId)).limit(1);
    return job ?? null;
  }

  async createJob(values: typeof documentParseJobs.$inferInsert, db: DatabaseExecutor = this.database.db) {
    const [job] = await db.insert(documentParseJobs).values(values).returning();
    return job;
  }

  async updateJob(
    jobId: string,
    values: Partial<typeof documentParseJobs.$inferInsert>,
    db: DatabaseExecutor = this.database.db
  ) {
    const [job] = await db
      .update(documentParseJobs)
      .set(values)
      .where(eq(documentParseJobs.id, jobId))
      .returning();

    return job;
  }

  async getNextAttempt(documentId: string, db: DatabaseExecutor = this.database.db): Promise<number> {
    const [latestJob] = await db
      .select({ attempt: documentParseJobs.attempt })
      .from(documentParseJobs)
      .where(eq(documentParseJobs.documentId, documentId))
      .orderBy(desc(documentParseJobs.attempt))
      .limit(1);

    return (latestJob?.attempt ?? 0) + 1;
  }
}
