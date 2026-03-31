import { Injectable } from "@nestjs/common";
import { eq } from "drizzle-orm";
import { DatabaseService, type DatabaseClient } from "../database/database.service";
import { uploads } from "../database/schema";

type DatabaseExecutor = Pick<DatabaseClient, "select" | "insert" | "update">;

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

  async markUploadCompleted(
    uploadId: string,
    checksumSha256: string,
    db: DatabaseExecutor = this.database.db
  ) {
    const [upload] = await db
      .update(uploads)
      .set({
        checksumSha256,
        status: "completed",
        completedAt: new Date()
      })
      .where(eq(uploads.id, uploadId))
      .returning();

    return upload;
  }
}
