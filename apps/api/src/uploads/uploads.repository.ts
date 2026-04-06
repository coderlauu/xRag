import { Injectable } from "@nestjs/common";
import { randomUUID } from "node:crypto";
import { and, desc, eq, inArray, sql } from "drizzle-orm";
import { DatabaseService, type DatabaseClient } from "../database/database.service";
import { uploadParts, uploads } from "../database/schema";

type DatabaseExecutor = Pick<DatabaseClient, "select" | "insert" | "update" | "delete">;

@Injectable()
export class UploadsRepository {
  constructor(private readonly database: DatabaseService) {}

  async createUpload(values: typeof uploads.$inferInsert, db: DatabaseExecutor = this.database.db) {
    const [upload] = await db.insert(uploads).values(values).returning();
    return upload;
  }

  async getUploadById(uploadId: string, db: DatabaseExecutor = this.database.db) {
    const [upload] = await db.select().from(uploads).where(eq(uploads.id, uploadId)).limit(1);
    return upload ?? null;
  }

  async updateUpload(
    uploadId: string,
    values: Partial<typeof uploads.$inferInsert>,
    db: DatabaseExecutor = this.database.db
  ) {
    const [upload] = await db.update(uploads).set(values).where(eq(uploads.id, uploadId)).returning();
    return upload;
  }

  async listUploadsByIds(uploadIds: string[], db: DatabaseExecutor = this.database.db) {
    if (uploadIds.length === 0) {
      return [];
    }

    return db.select().from(uploads).where(inArray(uploads.id, uploadIds));
  }

  async ensureUploadParts(uploadId: string, partNumbers: number[], db: DatabaseExecutor = this.database.db) {
    if (partNumbers.length === 0) {
      return;
    }

    await db
      .insert(uploadParts)
      .values(
        partNumbers.map((partNumber) => ({
          id: randomUUID(),
          uploadId,
          partNumber
        }))
      )
      .onConflictDoNothing({
        target: [uploadParts.uploadId, uploadParts.partNumber]
      });
  }

  async completeUploadPart(
    uploadId: string,
    partNumber: number,
    values: {
      etag: string;
      sizeBytes: number;
    },
    db: DatabaseExecutor = this.database.db
  ) {
    await db
      .insert(uploadParts)
      .values({
        id: randomUUID(),
        uploadId,
        partNumber,
        etag: values.etag,
        sizeBytes: values.sizeBytes,
        status: "uploaded"
      })
      .onConflictDoUpdate({
        target: [uploadParts.uploadId, uploadParts.partNumber],
        set: {
          etag: values.etag,
          sizeBytes: values.sizeBytes,
          status: "uploaded",
          errorCode: null,
          errorMessage: null,
          updatedAt: new Date()
        }
      });

    const [summary] = await db
      .select({
        uploadedPartCount: sql<number>`count(*)::int`
      })
      .from(uploadParts)
      .where(and(eq(uploadParts.uploadId, uploadId), eq(uploadParts.status, "uploaded")));

    await db
      .update(uploads)
      .set({
        status: "uploading",
        uploadedPartCount: summary?.uploadedPartCount ?? 0
      })
      .where(eq(uploads.id, uploadId));

    return {
      uploadedPartCount: summary?.uploadedPartCount ?? 0
    };
  }

  async listUploadParts(uploadId: string, db: DatabaseExecutor = this.database.db) {
    return db
      .select()
      .from(uploadParts)
      .where(eq(uploadParts.uploadId, uploadId))
      .orderBy(uploadParts.partNumber);
  }

  async getLatestCompletedUploadPart(uploadId: string, db: DatabaseExecutor = this.database.db) {
    const [part] = await db
      .select()
      .from(uploadParts)
      .where(and(eq(uploadParts.uploadId, uploadId), eq(uploadParts.status, "uploaded")))
      .orderBy(desc(uploadParts.updatedAt))
      .limit(1);

    return part ?? null;
  }

  async listRecentFailedUploads(limit = 20, db: DatabaseExecutor = this.database.db) {
    return db
      .select()
      .from(uploads)
      .where(eq(uploads.status, "failed"))
      .orderBy(desc(uploads.completedAt), desc(uploads.createdAt))
      .limit(limit);
  }
}
