import { relations } from "drizzle-orm";
import {
  bigint,
  boolean,
  char,
  customType,
  index,
  integer,
  jsonb,
  numeric,
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

const vector1536 = customType<{ data: string }>({
  dataType() {
    return "vector(1536)";
  }
});

export const sourceTypeEnum = pgEnum("source_type", ["text", "file", "pdf", "link"]);
export const sourceOriginEnum = pgEnum("source_origin", ["manual_input", "upload", "link"]);
export const parseStatusEnum = pgEnum("parse_status", ["pending", "processing", "success", "failed"]);
export const ocrStatusEnum = pgEnum("ocr_status", ["not_required", "queued", "processing", "success", "failed"]);
export const tagStatusEnum = pgEnum("tag_status", ["active", "archived"]);
export const uploadStatusEnum = pgEnum("upload_status", [
  "draft",
  "initiated",
  "uploading",
  "verifying",
  "uploaded",
  "failed",
  "expired"
]);
export const uploadModeEnum = pgEnum("upload_mode", ["single", "multipart"]);
export const jobTypeEnum = pgEnum("job_type", [
  "parse_document",
  "reparse_document",
  "refresh_search_projection",
  "run_ocr",
  "fetch_link",
  "rebuild_search_projection",
  "chunk_document",
  "embed_document"
]);
export const jobStatusEnum = pgEnum("job_status", ["queued", "running", "succeeded", "failed", "dead"]);
export const uploadPartStatusEnum = pgEnum("upload_part_status", ["initiated", "uploaded", "failed"]);
export const sourceFetchStatusEnum = pgEnum("source_fetch_status", [
  "queued",
  "fetching",
  "extracting",
  "success",
  "failed"
]);
export const processingEventStageEnum = pgEnum("processing_event_stage", [
  "upload",
  "parse",
  "ocr",
  "fetch",
  "projection",
  "ops",
  "index"
]);
export const indexStatusEnum = pgEnum("index_status", [
  "not_indexed",
  "queued",
  "chunking",
  "embedding",
  "ready",
  "failed",
  "stale"
]);
export const answerScopeModeEnum = pgEnum("answer_scope_mode", ["global", "search_result", "document"]);
export const retrievalModeEnum = pgEnum("retrieval_mode", ["hybrid"]);
export const answerSessionStatusEnum = pgEnum("answer_session_status", [
  "idle",
  "retrieving",
  "synthesizing",
  "answered",
  "needs_scope",
  "refused",
  "failed"
]);

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
    indexStatus: indexStatusEnum("index_status").notNull().default("not_indexed"),
    indexVersion: varchar("index_version", { length: 64 }),
    indexedAt: timestamp("indexed_at", { withTimezone: true }),
    citationReady: boolean("citation_ready").notNull().default(false),
    ocrStatus: ocrStatusEnum("ocr_status"),
    ocrEngine: varchar("ocr_engine", { length: 64 }),
    ocrLanguage: varchar("ocr_language", { length: 64 }),
    uploadStatus: uploadStatusEnum("upload_status"),
    diagnosisCode: varchar("diagnosis_code", { length: 64 }),
    diagnosisSummary: text("diagnosis_summary"),
    matchedFields: jsonb("matched_fields").$type<string[] | null>(),
    matchExplanation: text("match_explanation"),
    rankingHint: text("ranking_hint"),
    uploadId: uuid("upload_id"),
    pageCount: integer("page_count"),
    parserName: varchar("parser_name", { length: 64 }),
    parserVersion: varchar("parser_version", { length: 64 }),
    lastIncidentRef: varchar("last_incident_ref", { length: 64 }),
    timelineCursor: varchar("timeline_cursor", { length: 64 }),
    parseErrorMessage: text("parse_error_message"),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    importedAt: timestamp("imported_at", { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp("updated_at", { withTimezone: true }).notNull().defaultNow()
  },
  (table) => ({
    parseStatusIdx: index("idx_documents_parse_status").on(table.parseStatus),
    indexStatusIdx: index("idx_documents_index_status").on(table.indexStatus),
    sourceTypeIdx: index("idx_documents_source_type").on(table.sourceType),
    ocrStatusIdx: index("idx_documents_ocr_status").on(table.ocrStatus),
    uploadStatusIdx: index("idx_documents_upload_status").on(table.uploadStatus),
    diagnosisCodeIdx: index("idx_documents_diagnosis_code").on(table.diagnosisCode),
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
    uploadMode: uploadModeEnum("upload_mode").notNull().default("single"),
    status: uploadStatusEnum("status").notNull().default("initiated"),
    providerUploadId: varchar("provider_upload_id", { length: 255 }),
    partCount: integer("part_count"),
    uploadedPartCount: integer("uploaded_part_count").notNull().default(0),
    verifiedAt: timestamp("verified_at", { withTimezone: true }),
    errorCode: varchar("error_code", { length: 64 }),
    errorMessage: text("error_message"),
    completedByClientAt: timestamp("completed_by_client_at", { withTimezone: true }),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    completedAt: timestamp("completed_at", { withTimezone: true })
  },
  (table) => ({
    statusIdx: index("idx_uploads_status").on(table.status),
    providerUploadIdIdx: index("idx_uploads_provider_upload_id").on(table.providerUploadId)
  })
);

export const uploadParts = pgTable(
  "upload_parts",
  {
    id: uuid("id").primaryKey(),
    uploadId: uuid("upload_id")
      .notNull()
      .references(() => uploads.id, { onDelete: "cascade" }),
    partNumber: integer("part_number").notNull(),
    etag: varchar("etag", { length: 255 }),
    sizeBytes: bigint("size_bytes", { mode: "number" }),
    status: uploadPartStatusEnum("status").notNull().default("initiated"),
    errorCode: varchar("error_code", { length: 64 }),
    errorMessage: text("error_message"),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    updatedAt: timestamp("updated_at", { withTimezone: true }).notNull().defaultNow()
  },
  (table) => ({
    uploadPartUniqueIdx: uniqueIndex("idx_upload_parts_upload_part_number").on(table.uploadId, table.partNumber)
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
    diagnosisCode: varchar("diagnosis_code", { length: 64 }),
    incidentRef: varchar("incident_ref", { length: 64 }),
    workerName: varchar("worker_name", { length: 64 }),
    runtimeMs: integer("runtime_ms"),
    attemptGroup: varchar("attempt_group", { length: 64 }),
    startedAt: timestamp("started_at", { withTimezone: true }),
    finishedAt: timestamp("finished_at", { withTimezone: true }),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow()
  },
  (table) => ({
    documentIdIdx: index("idx_document_parse_jobs_document_id").on(table.documentId),
    statusIdx: index("idx_document_parse_jobs_status").on(table.status),
    diagnosisCodeIdx: index("idx_document_parse_jobs_diagnosis_code").on(table.diagnosisCode)
  })
);

export const documentSourceFetches = pgTable(
  "document_source_fetches",
  {
    id: uuid("id").primaryKey(),
    documentId: uuid("document_id")
      .notNull()
      .references(() => documents.id, { onDelete: "cascade" }),
    sourceUrl: text("source_url").notNull(),
    fetchStatus: sourceFetchStatusEnum("fetch_status").notNull().default("queued"),
    httpStatus: integer("http_status"),
    contentType: varchar("content_type", { length: 128 }),
    canonicalUrl: text("canonical_url"),
    titleExtracted: varchar("title_extracted", { length: 255 }),
    diagnosisCode: varchar("diagnosis_code", { length: 64 }),
    errorMessage: text("error_message"),
    startedAt: timestamp("started_at", { withTimezone: true }),
    finishedAt: timestamp("finished_at", { withTimezone: true }),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow()
  },
  (table) => ({
    documentIdIdx: index("idx_document_source_fetches_document_id").on(table.documentId)
  })
);

export const documentProcessingEvents = pgTable(
  "document_processing_events",
  {
    id: uuid("id").primaryKey(),
    documentId: uuid("document_id")
      .notNull()
      .references(() => documents.id, { onDelete: "cascade" }),
    eventType: varchar("event_type", { length: 64 }).notNull(),
    stage: processingEventStageEnum("stage").notNull(),
    status: parseStatusEnum("status").notNull(),
    diagnosisCode: varchar("diagnosis_code", { length: 64 }),
    summary: text("summary").notNull(),
    payload: jsonb("payload").$type<Record<string, unknown> | null>(),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow()
  },
  (table) => ({
    documentCreatedIdx: index("idx_document_processing_events_document_created").on(
      table.documentId,
      table.createdAt
    ),
    stageStatusIdx: index("idx_document_processing_events_stage_status").on(table.stage, table.status)
  })
);

export const documentChunks = pgTable(
  "document_chunks",
  {
    id: uuid("id").primaryKey(),
    documentId: uuid("document_id")
      .notNull()
      .references(() => documents.id, { onDelete: "cascade" }),
    chunkIndex: integer("chunk_index").notNull(),
    strategyVersion: varchar("strategy_version", { length: 64 }).notNull(),
    sectionLabel: varchar("section_label", { length: 128 }),
    pageRef: varchar("page_ref", { length: 64 }),
    contentText: text("content_text").notNull(),
    tokenCount: integer("token_count").notNull(),
    contentSha256: char("content_sha256", { length: 64 }).notNull(),
    embedding: vector1536("embedding"),
    citationLocator: jsonb("citation_locator").$type<Record<string, unknown> | null>(),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow()
  },
  (table) => ({
    documentIdIdx: index("idx_document_chunks_document_id").on(table.documentId),
    documentChunkUniqueIdx: uniqueIndex("idx_document_chunks_document_chunk_index").on(
      table.documentId,
      table.chunkIndex
    )
  })
);

export const answerSessions = pgTable(
  "answer_sessions",
  {
    id: uuid("id").primaryKey(),
    ownerId: uuid("owner_id"),
    queueJobId: varchar("queue_job_id", { length: 128 }),
    question: text("question").notNull(),
    scopeMode: answerScopeModeEnum("scope_mode").notNull(),
    scopePayload: jsonb("scope_payload").$type<Record<string, unknown> | null>(),
    retrievalMode: retrievalModeEnum("retrieval_mode").notNull().default("hybrid"),
    status: answerSessionStatusEnum("status").notNull().default("idle"),
    answerSummary: text("answer_summary"),
    refusalReason: text("refusal_reason"),
    diagnosisCode: varchar("diagnosis_code", { length: 64 }),
    providerName: varchar("provider_name", { length: 64 }),
    providerModel: varchar("provider_model", { length: 128 }),
    latencyMs: integer("latency_ms"),
    promptTokens: integer("prompt_tokens"),
    completionTokens: integer("completion_tokens"),
    totalCostUsd: numeric("total_cost_usd", { precision: 10, scale: 4 }),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow(),
    finishedAt: timestamp("finished_at", { withTimezone: true })
  },
  (table) => ({
    statusIdx: index("idx_answer_sessions_status").on(table.status),
    createdAtIdx: index("idx_answer_sessions_created_at").on(table.createdAt)
  })
);

export const retrievalRuns = pgTable(
  "retrieval_runs",
  {
    id: uuid("id").primaryKey(),
    sessionId: uuid("session_id")
      .notNull()
      .references(() => answerSessions.id, { onDelete: "cascade" }),
    queryNormalized: text("query_normalized").notNull(),
    eligibleDocumentCount: integer("eligible_document_count").notNull(),
    lexicalHitCount: integer("lexical_hit_count").notNull(),
    semanticHitCount: integer("semantic_hit_count").notNull(),
    mergedHitCount: integer("merged_hit_count").notNull(),
    rerankStrategy: varchar("rerank_strategy", { length: 64 }).notNull().default("hybrid"),
    latencyMs: integer("latency_ms"),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow()
  },
  (table) => ({
    sessionIdIdx: index("idx_retrieval_runs_session_id").on(table.sessionId),
    createdAtIdx: index("idx_retrieval_runs_created_at").on(table.createdAt)
  })
);

export const retrievalRunHits = pgTable(
  "retrieval_run_hits",
  {
    id: uuid("id").primaryKey(),
    retrievalRunId: uuid("retrieval_run_id")
      .notNull()
      .references(() => retrievalRuns.id, { onDelete: "cascade" }),
    documentId: uuid("document_id")
      .notNull()
      .references(() => documents.id, { onDelete: "cascade" }),
    chunkId: uuid("chunk_id").references(() => documentChunks.id, { onDelete: "cascade" }),
    rank: integer("rank").notNull(),
    lexicalScore: numeric("lexical_score", { precision: 10, scale: 4 }),
    semanticScore: numeric("semantic_score", { precision: 10, scale: 4 }),
    finalScore: numeric("final_score", { precision: 10, scale: 4 }),
    usedInAnswer: boolean("used_in_answer").notNull().default(false),
    exclusionReason: varchar("exclusion_reason", { length: 64 }),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow()
  },
  (table) => ({
    retrievalRunIdIdx: index("idx_retrieval_run_hits_retrieval_run_id").on(table.retrievalRunId),
    documentIdIdx: index("idx_retrieval_run_hits_document_id").on(table.documentId),
    rankIdx: index("idx_retrieval_run_hits_rank").on(table.rank)
  })
);

export const answerCitations = pgTable(
  "answer_citations",
  {
    id: uuid("id").primaryKey(),
    sessionId: uuid("session_id")
      .notNull()
      .references(() => answerSessions.id, { onDelete: "cascade" }),
    documentId: uuid("document_id")
      .notNull()
      .references(() => documents.id, { onDelete: "cascade" }),
    chunkId: uuid("chunk_id")
      .notNull()
      .references(() => documentChunks.id, { onDelete: "cascade" }),
    claimSlot: varchar("claim_slot", { length: 64 }).notNull(),
    quoteText: text("quote_text").notNull(),
    locator: jsonb("locator").$type<Record<string, unknown> | null>(),
    createdAt: timestamp("created_at", { withTimezone: true }).notNull().defaultNow()
  },
  (table) => ({
    sessionIdIdx: index("idx_answer_citations_session_id").on(table.sessionId),
    documentIdIdx: index("idx_answer_citations_document_id").on(table.documentId),
    chunkIdIdx: index("idx_answer_citations_chunk_id").on(table.chunkId)
  })
);

export const documentsRelations = relations(documents, ({ many, one }) => ({
  documentTags: many(documentTags),
  parseJobs: many(documentParseJobs),
  sourceFetches: many(documentSourceFetches),
  processingEvents: many(documentProcessingEvents),
  chunks: many(documentChunks),
  retrievalRunHits: many(retrievalRunHits),
  answerCitations: many(answerCitations),
  upload: one(uploads, {
    fields: [documents.uploadId],
    references: [uploads.id]
  })
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

export const uploadsRelations = relations(uploads, ({ many }) => ({
  parts: many(uploadParts)
}));

export const uploadPartsRelations = relations(uploadParts, ({ one }) => ({
  upload: one(uploads, {
    fields: [uploadParts.uploadId],
    references: [uploads.id]
  })
}));

export const documentParseJobsRelations = relations(documentParseJobs, ({ one }) => ({
  document: one(documents, {
    fields: [documentParseJobs.documentId],
    references: [documents.id]
  })
}));

export const documentSourceFetchesRelations = relations(documentSourceFetches, ({ one }) => ({
  document: one(documents, {
    fields: [documentSourceFetches.documentId],
    references: [documents.id]
  })
}));

export const documentProcessingEventsRelations = relations(documentProcessingEvents, ({ one }) => ({
  document: one(documents, {
    fields: [documentProcessingEvents.documentId],
    references: [documents.id]
  })
}));

export const documentChunksRelations = relations(documentChunks, ({ many, one }) => ({
  document: one(documents, {
    fields: [documentChunks.documentId],
    references: [documents.id]
  }),
  retrievalRunHits: many(retrievalRunHits),
  answerCitations: many(answerCitations)
}));

export const answerSessionsRelations = relations(answerSessions, ({ many }) => ({
  retrievalRuns: many(retrievalRuns),
  citations: many(answerCitations)
}));

export const retrievalRunsRelations = relations(retrievalRuns, ({ many, one }) => ({
  session: one(answerSessions, {
    fields: [retrievalRuns.sessionId],
    references: [answerSessions.id]
  }),
  hits: many(retrievalRunHits)
}));

export const retrievalRunHitsRelations = relations(retrievalRunHits, ({ one }) => ({
  retrievalRun: one(retrievalRuns, {
    fields: [retrievalRunHits.retrievalRunId],
    references: [retrievalRuns.id]
  }),
  document: one(documents, {
    fields: [retrievalRunHits.documentId],
    references: [documents.id]
  }),
  chunk: one(documentChunks, {
    fields: [retrievalRunHits.chunkId],
    references: [documentChunks.id]
  })
}));

export const answerCitationsRelations = relations(answerCitations, ({ one }) => ({
  session: one(answerSessions, {
    fields: [answerCitations.sessionId],
    references: [answerSessions.id]
  }),
  document: one(documents, {
    fields: [answerCitations.documentId],
    references: [documents.id]
  }),
  chunk: one(documentChunks, {
    fields: [answerCitations.chunkId],
    references: [documentChunks.id]
  })
}));

export const schema = {
  documents,
  tags,
  documentTags,
  uploads,
  uploadParts,
  documentParseJobs,
  documentSourceFetches,
  documentProcessingEvents,
  documentChunks,
  answerSessions,
  retrievalRuns,
  retrievalRunHits,
  answerCitations
};
