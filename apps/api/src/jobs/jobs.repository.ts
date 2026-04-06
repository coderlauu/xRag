import { Injectable } from "@nestjs/common";
import { and, desc, eq, inArray } from "drizzle-orm";
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

  async getLatestJobByDocumentId(documentId: string, db: DatabaseExecutor = this.database.db) {
    const [job] = await db
      .select()
      .from(documentParseJobs)
      .where(eq(documentParseJobs.documentId, documentId))
      .orderBy(desc(documentParseJobs.createdAt), desc(documentParseJobs.attempt))
      .limit(1);

    return job ?? null;
  }

  async listLatestJobsByDocumentIds(documentIds: string[], db: DatabaseExecutor = this.database.db) {
    if (documentIds.length === 0) {
      return [];
    }

    const jobs = await db
      .select()
      .from(documentParseJobs)
      .where(inArray(documentParseJobs.documentId, documentIds))
      .orderBy(desc(documentParseJobs.createdAt), desc(documentParseJobs.attempt));

    const latest = new Map<string, typeof documentParseJobs.$inferSelect>();
    for (const job of jobs) {
      if (!latest.has(job.documentId)) {
        latest.set(job.documentId, job);
      }
    }

    return documentIds
      .map((documentId) => latest.get(documentId))
      .filter((job): job is typeof documentParseJobs.$inferSelect => Boolean(job));
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

  async listRecentIncidentCandidates(limit = 20, db: DatabaseExecutor = this.database.db) {
    return db
      .select()
      .from(documentParseJobs)
      .where(inArray(documentParseJobs.status, ["failed", "dead"]))
      .orderBy(desc(documentParseJobs.finishedAt), desc(documentParseJobs.createdAt))
      .limit(limit);
  }
}
