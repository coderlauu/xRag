import { relations } from "drizzle-orm";
import {
  bigint,
  char,
  customType,
  index,
  integer,
  pgEnum,
  pgTable,
  primaryKey,
  text,
  timestamp,
  uniqueIndex,
  uuid,
  varchar
} from "drizzle-orm/pg-core";

const tsvector = customType<{ data: string }>({
  dataType() {
    return "tsvector";
  }
});

export const sourceTypeEnum = pgEnum("source_type", ["text", "file", "link"]);
export const sourceOriginEnum = pgEnum("source_origin", ["manual_input", "upload", "link"]);
export const parseStatusEnum = pgEnum("parse_status", ["pending", "processing", "success", "failed"]);
export const tagStatusEnum = pgEnum("tag_status", ["active", "archived"]);
export const uploadStatusEnum = pgEnum("upload_status", ["initiated", "uploaded", "completed", "expired"]);
export const jobTypeEnum = pgEnum("job_type", [
  "parse_document",
  "reparse_document",
  "refresh_search_projection"
]);
export const jobStatusEnum = pgEnum("job_status", ["queued", "running", "succeeded", "failed", "dead"]);

export const documents = pgTable(
  "documents",
  {
    id: uuid("id").primaryKey(),
    ownerId: uuid("owner_id"),
    title: varchar("title", { length: 255 }).notNull(),
    contentRaw: text("content_raw"),
    contentClean: text("content_clean"),
    contentPreview: text("content_preview"),
    searchText: text("search_text"),
    searchVector: tsvector("search_vector"),
    sourceType: sourceTypeEnum("source_type").notNull(),
    sourceOrigin: sourceOriginEnum("source_origin").notNull(),
    sourceUrl: text("source_url"),
    fileName: text("file_name"),
    mimeType: varchar("mime_type", { length: 255 }),
    fileSize: bigint("file_size", { mode: "number" }),
    objectKey: text("object_key"),
    contentSha256: char("content_sha256", { length: 64 }),
    parseStatus: parseStatusEnum("parse_status").notNull().default("pending"),
    parseErrorMessage: text("parse_error_message"),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    importedAt: timestamp("imported_at", { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp("updated_at", { withTimezone: true }).notNull().defaultNow()
  },
  (table) => ({
    parseStatusIdx: index("idx_documents_parse_status").on(table.parseStatus),
    importedAtIdx: index("idx_documents_imported_at").on(table.importedAt)
  })
);

export const tags = pgTable(
  "tags",
  {
    id: uuid("id").primaryKey(),
    ownerId: uuid("owner_id"),
    name: varchar("name", { length: 64 }).notNull(),
    normalizedName: varchar("normalized_name", { length: 64 }).notNull(),
    status: tagStatusEnum("status").notNull().default("active"),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp("updated_at", { withTimezone: true }).notNull().defaultNow()
  },
  (table) => ({
    ownerNormalizedNameIdx: uniqueIndex("idx_tags_owner_normalized_name").on(
      table.ownerId,
      table.normalizedName
    )
  })
);

export const documentTags = pgTable(
  "document_tags",
  {
    documentId: uuid("document_id")
      .notNull()
      .references(() => documents.id, { onDelete: "cascade" }),
    tagId: uuid("tag_id")
      .notNull()
      .references(() => tags.id, { onDelete: "restrict" }),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow()
  },
  (table) => ({
    documentTagPk: primaryKey({
      name: "pk_document_tags",
      columns: [table.documentId, table.tagId]
    }),
    tagIdIdx: index("idx_document_tags_tag_id").on(table.tagId)
  })
);

export const uploads = pgTable(
  "uploads",
  {
    id: uuid("id").primaryKey(),
    ownerId: uuid("owner_id"),
    fileName: text("file_name").notNull(),
    mimeType: varchar("mime_type", { length: 255 }).notNull(),
    fileSize: bigint("file_size", { mode: "number" }).notNull(),
    objectKey: text("object_key").notNull(),
    checksumSha256: char("checksum_sha256", { length: 64 }),
    status: uploadStatusEnum("status").notNull().default("initiated"),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    completedAt: timestamp("completed_at", { withTimezone: true })
  },
  (table) => ({
    statusIdx: index("idx_uploads_status").on(table.status)
  })
);

export const documentParseJobs = pgTable(
  "document_parse_jobs",
  {
    id: uuid("id").primaryKey(),
    documentId: uuid("document_id")
      .notNull()
      .references(() => documents.id, { onDelete: "cascade" }),
    queueJobId: varchar("queue_job_id", { length: 128 }),
    jobType: jobTypeEnum("job_type").notNull(),
    status: jobStatusEnum("status").notNull().default("queued"),
    attempt: integer("attempt").notNull().default(1),
    errorCode: varchar("error_code", { length: 64 }),
    errorMessage: text("error_message"),
    startedAt: timestamp("started_at", { withTimezone: true }),
    finishedAt: timestamp("finished_at", { withTimezone: true }),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow()
  },
  (table) => ({
    documentIdIdx: index("idx_document_parse_jobs_document_id").on(table.documentId),
    statusIdx: index("idx_document_parse_jobs_status").on(table.status)
  })
);

export const documentsRelations = relations(documents, ({ many }) => ({
  documentTags: many(documentTags),
  parseJobs: many(documentParseJobs)
}));

export const tagsRelations = relations(tags, ({ many }) => ({
  documentTags: many(documentTags)
}));

export const documentTagsRelations = relations(documentTags, ({ one }) => ({
  document: one(documents, {
    fields: [documentTags.documentId],
    references: [documents.id]
  }),
  tag: one(tags, {
    fields: [documentTags.tagId],
    references: [tags.id]
  })
}));

export const documentParseJobsRelations = relations(documentParseJobs, ({ one }) => ({
  document: one(documents, {
    fields: [documentParseJobs.documentId],
    references: [documents.id]
  })
}));

export const schema = {
  documents,
  tags,
  documentTags,
  uploads,
  documentParseJobs
};
