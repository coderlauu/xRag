ALTER TYPE "public"."source_type" ADD VALUE IF NOT EXISTS 'pdf';--> statement-breakpoint
ALTER TYPE "public"."job_type" ADD VALUE IF NOT EXISTS 'run_ocr';--> statement-breakpoint
ALTER TYPE "public"."job_type" ADD VALUE IF NOT EXISTS 'fetch_link';--> statement-breakpoint
ALTER TYPE "public"."job_type" ADD VALUE IF NOT EXISTS 'rebuild_search_projection';--> statement-breakpoint
CREATE TYPE "public"."ocr_status" AS ENUM('not_required', 'queued', 'processing', 'success', 'failed');--> statement-breakpoint
CREATE TYPE "public"."source_fetch_status" AS ENUM('queued', 'fetching', 'extracting', 'success', 'failed');--> statement-breakpoint
CREATE TYPE "public"."processing_event_stage" AS ENUM('upload', 'parse', 'ocr', 'fetch', 'projection', 'ops');--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "ocr_status" "ocr_status";--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "ocr_engine" varchar(64);--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "ocr_language" varchar(64);--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "matched_fields" jsonb;--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "match_explanation" text;--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "ranking_hint" text;--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "timeline_cursor" varchar(64);--> statement-breakpoint
ALTER TABLE "document_parse_jobs" ADD COLUMN "attempt_group" varchar(64);--> statement-breakpoint
CREATE TABLE "document_source_fetches" (
  "id" uuid PRIMARY KEY NOT NULL,
  "document_id" uuid NOT NULL,
  "source_url" text NOT NULL,
  "fetch_status" "source_fetch_status" DEFAULT 'queued' NOT NULL,
  "http_status" integer,
  "content_type" varchar(128),
  "canonical_url" text,
  "title_extracted" varchar(255),
  "diagnosis_code" varchar(64),
  "error_message" text,
  "started_at" timestamp with time zone,
  "finished_at" timestamp with time zone,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL
);--> statement-breakpoint
CREATE TABLE "document_processing_events" (
  "id" uuid PRIMARY KEY NOT NULL,
  "document_id" uuid NOT NULL,
  "event_type" varchar(64) NOT NULL,
  "stage" "processing_event_stage" NOT NULL,
  "status" "parse_status" NOT NULL,
  "diagnosis_code" varchar(64),
  "summary" text NOT NULL,
  "payload" jsonb,
  "created_at" timestamp with time zone DEFAULT now() NOT NULL
);--> statement-breakpoint
ALTER TABLE "document_source_fetches" ADD CONSTRAINT "document_source_fetches_document_id_documents_id_fk" FOREIGN KEY ("document_id") REFERENCES "public"."documents"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "document_processing_events" ADD CONSTRAINT "document_processing_events_document_id_documents_id_fk" FOREIGN KEY ("document_id") REFERENCES "public"."documents"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "idx_documents_source_type" ON "documents" USING btree ("source_type");--> statement-breakpoint
CREATE INDEX "idx_documents_ocr_status" ON "documents" USING btree ("ocr_status");--> statement-breakpoint
CREATE INDEX "idx_document_source_fetches_document_id" ON "document_source_fetches" USING btree ("document_id");--> statement-breakpoint
CREATE INDEX "idx_document_processing_events_document_created" ON "document_processing_events" USING btree ("document_id","created_at");--> statement-breakpoint
CREATE INDEX "idx_document_processing_events_stage_status" ON "document_processing_events" USING btree ("stage","status");
