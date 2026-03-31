import { Injectable } from "@nestjs/common";
import {
  and,
  asc,
  desc,
  eq,
  exists,
  gte,
  inArray,
  lte,
  or,
  sql,
  type SQL
} from "drizzle-orm";
import type { ParseStatus, SourceType } from "@xrag/shared-types";
import { normalizeTagName } from "../common/document-utils";
import { DatabaseService, type DatabaseClient } from "../database/database.service";
import { documentTags, documents, tags } from "../database/schema";

type DatabaseExecutor = Pick<DatabaseClient, "select" | "insert" | "update" | "delete">;

export interface ListDocumentsFilters {
  q?: string;
  sourceType?: SourceType;
  parseStatuses: ParseStatus[];
  tags: string[];
  dateFrom?: string;
  dateTo?: string;
  page: number;
  pageSize: number;
}

@Injectable()
export class DocumentsRepository {
  constructor(private readonly database: DatabaseService) {}

  async createDocument(values: typeof documents.$inferInsert, db: DatabaseExecutor = this.database.db) {
    const [document] = await db.insert(documents).values(values).returning();
    return document;
  }

  async getDocumentById(documentId: string, db: DatabaseExecutor = this.database.db) {
    const [document] = await db.select().from(documents).where(eq(documents.id, documentId)).limit(1);
    return document ?? null;
  }

  async listDocuments(filters: ListDocumentsFilters, db: DatabaseExecutor = this.database.db) {
    const whereClause = this.buildWhere(filters, db);
    const [{ total }] = await db
      .select({
        total: sql<number>`count(*)::int`
      })
      .from(documents)
      .where(whereClause);

    const items = await db
      .select()
      .from(documents)
      .where(whereClause)
      .orderBy(desc(documents.importedAt), desc(documents.createdAt))
      .limit(filters.pageSize)
      .offset((filters.page - 1) * filters.pageSize);

    return {
      items,
      total
    };
  }

  async replaceDocumentTags(documentId: string, tagIds: string[], db: DatabaseExecutor = this.database.db) {
    await db.delete(documentTags).where(eq(documentTags.documentId, documentId));

    if (tagIds.length === 0) {
      return;
    }

    await db.insert(documentTags).values(
      tagIds.map((tagId) => ({
        documentId,
        tagId
      }))
    );
  }

  async updateDocumentProjection(
    documentId: string,
    values: Partial<typeof documents.$inferInsert>,
    db: DatabaseExecutor = this.database.db
  ) {
    const [document] = await db
      .update(documents)
      .set({
        ...values,
        searchVector:
          values.searchText === undefined ? undefined : sql`to_tsvector('simple', ${values.searchText})`,
        updatedAt: new Date()
      })
      .where(eq(documents.id, documentId))
      .returning();

    return document;
  }

  async loadTagsByDocumentIds(documentIds: string[], db: DatabaseExecutor = this.database.db) {
    if (documentIds.length === 0) {
      return new Map<string, string[]>();
    }

    const rows = await db
      .select({
        documentId: documentTags.documentId,
        tagName: tags.name
      })
      .from(documentTags)
      .innerJoin(tags, eq(documentTags.tagId, tags.id))
      .where(inArray(documentTags.documentId, documentIds))
      .orderBy(asc(tags.name));

    const tagMap = new Map<string, string[]>();
    for (const row of rows) {
      const current = tagMap.get(row.documentId) || [];
      current.push(row.tagName);
      tagMap.set(row.documentId, current);
    }

    return tagMap;
  }

  private buildWhere(filters: ListDocumentsFilters, db: DatabaseExecutor): SQL<unknown> | undefined {
    const conditions: SQL<unknown>[] = [];

    if (filters.q) {
      const likeQuery = `%${filters.q.trim()}%`;
      conditions.push(sql`coalesce(${documents.searchText}, '') ilike ${likeQuery}`);
    }

    if (filters.sourceType) {
      conditions.push(eq(documents.sourceType, filters.sourceType));
    }

    if (filters.parseStatuses.length > 0) {
      conditions.push(inArray(documents.parseStatus, filters.parseStatuses));
    }

    if (filters.dateFrom) {
      conditions.push(gte(documents.importedAt, new Date(filters.dateFrom)));
    }

    if (filters.dateTo) {
      conditions.push(lte(documents.importedAt, new Date(filters.dateTo)));
    }

    if (filters.tags.length > 0) {
      const tagIds = filters.tags.filter((token) => token.includes("-"));
      const tagNames = filters.tags.filter((token) => !token.includes("-")).map(normalizeTagName);
      const tagPredicates: SQL<unknown>[] = [];

      if (tagIds.length > 0) {
        tagPredicates.push(inArray(tags.id, tagIds));
      }

      if (tagNames.length > 0) {
        tagPredicates.push(inArray(tags.normalizedName, tagNames));
      }

      if (tagPredicates.length > 0) {
        const subquery = db
          .select({
            value: sql`1`
          })
          .from(documentTags)
          .innerJoin(tags, eq(documentTags.tagId, tags.id))
          .where(and(eq(documentTags.documentId, documents.id), or(...tagPredicates)));

        conditions.push(exists(subquery));
      }
    }

    if (conditions.length === 0) {
      return undefined;
    }

    return and(...conditions);
  }
}
