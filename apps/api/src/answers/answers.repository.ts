import { Injectable } from "@nestjs/common";
import { desc, eq, sql } from "drizzle-orm";
import { DatabaseService, type DatabaseClient } from "../database/database.service";
import { answerCitations, answerClaims, answerSessions, retrievalRunHits, retrievalRuns } from "../database/schema";

type DatabaseExecutor = Pick<DatabaseClient, "select" | "insert" | "update">;

@Injectable()
export class AnswersRepository {
  constructor(private readonly database: DatabaseService) {}

  async countAnswerSessions(db: DatabaseExecutor = this.database.db) {
    const [result] = await db
      .select({
        total: sql<number>`count(*)::int`
      })
      .from(answerSessions);

    return result?.total ?? 0;
  }

  async listAnswerSessions(page: number, pageSize: number, db: DatabaseExecutor = this.database.db) {
    return db
      .select()
      .from(answerSessions)
      .orderBy(desc(answerSessions.createdAt), desc(answerSessions.updatedAt))
      .limit(pageSize)
      .offset((page - 1) * pageSize);
  }

  async createAnswerSession(values: typeof answerSessions.$inferInsert, db: DatabaseExecutor = this.database.db) {
    const [session] = await db.insert(answerSessions).values(values).returning();
    return session;
  }

  async updateAnswerSession(
    sessionId: string,
    values: Partial<typeof answerSessions.$inferInsert>,
    db: DatabaseExecutor = this.database.db
  ) {
    const [session] = await db
      .update(answerSessions)
      .set({
        ...values,
        updatedAt: new Date()
      })
      .where(eq(answerSessions.id, sessionId))
      .returning();

    return session ?? null;
  }

  async getAnswerSessionById(sessionId: string, db: DatabaseExecutor = this.database.db) {
    const [session] = await db.select().from(answerSessions).where(eq(answerSessions.id, sessionId)).limit(1);
    return session ?? null;
  }

  async listClaimsBySessionId(sessionId: string, db: DatabaseExecutor = this.database.db) {
    return db
      .select()
      .from(answerClaims)
      .where(eq(answerClaims.sessionId, sessionId))
      .orderBy(answerClaims.displayOrder, answerClaims.createdAt);
  }

  async listCitationsBySessionId(sessionId: string, db: DatabaseExecutor = this.database.db) {
    return db
      .select()
      .from(answerCitations)
      .where(eq(answerCitations.sessionId, sessionId))
      .orderBy(answerCitations.claimSlot, answerCitations.createdAt);
  }

  async getLatestRetrievalRunBySessionId(sessionId: string, db: DatabaseExecutor = this.database.db) {
    const [run] = await db
      .select()
      .from(retrievalRuns)
      .where(eq(retrievalRuns.sessionId, sessionId))
      .orderBy(desc(retrievalRuns.createdAt))
      .limit(1);

    return run ?? null;
  }

  async listRetrievalHitsByRunId(runId: string, db: DatabaseExecutor = this.database.db) {
    return db
      .select()
      .from(retrievalRunHits)
      .where(eq(retrievalRunHits.retrievalRunId, runId))
      .orderBy(retrievalRunHits.rank);
  }
}
