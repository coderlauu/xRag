import { Injectable } from "@nestjs/common";
import { and, asc, eq, ilike, inArray, sql, type SQL } from "drizzle-orm";
import { randomUUID } from "node:crypto";
import { dedupeStrings, normalizeTagName, normalizeWhitespace } from "../common/document-utils";
import { DatabaseService, type DatabaseClient } from "../database/database.service";
import { tags } from "../database/schema";

type DatabaseExecutor = Pick<DatabaseClient, "select" | "insert">;

@Injectable()
export class TagsRepository {
  constructor(private readonly database: DatabaseService) {}

  async listTags(
    filters: { q?: string; status?: "active" | "archived" },
    db: DatabaseExecutor = this.database.db
  ) {
    const conditions: SQL<unknown>[] = [];

    if (filters.q) {
      conditions.push(ilike(tags.name, `%${filters.q.trim()}%`));
    }

    if (filters.status) {
      conditions.push(eq(tags.status, filters.status));
    }

    return db
      .select({
        id: tags.id,
        name: tags.name,
        status: tags.status
      })
      .from(tags)
      .where(conditions.length > 0 ? and(...conditions) : undefined)
      .orderBy(asc(tags.name));
  }

  async upsertTags(names: string[], db: DatabaseExecutor = this.database.db) {
    const normalizedInputs = dedupeStrings(
      names
        .map((name) => normalizeWhitespace(name))
        .filter(Boolean)
    ).map((name) => ({
      id: randomUUID(),
      name,
      normalizedName: normalizeTagName(name)
    }));

    if (normalizedInputs.length === 0) {
      return [] as Array<typeof tags.$inferSelect>;
    }

    await db
      .insert(tags)
      .values(normalizedInputs)
      .onConflictDoUpdate({
        target: [tags.ownerId, tags.normalizedName],
        set: {
          name: sql`excluded.name`,
          updatedAt: new Date()
        }
      });

    return db
      .select()
      .from(tags)
      .where(
        inArray(
          tags.normalizedName,
          normalizedInputs.map((item) => item.normalizedName)
        )
      )
      .orderBy(asc(tags.name));
  }
}
