CREATE EXTENSION IF NOT EXISTS pg_trgm;--> statement-breakpoint
CREATE TYPE "public"."job_status" AS ENUM('queued', 'running', 'succeeded', 'failed', 'dead');--> statement-breakpoint
CREATE TYPE "public"."job_type" AS ENUM('parse_document', 'reparse_document', 'refresh_search_projection');--> statement-breakpoint
CREATE TYPE "public"."parse_status" AS ENUM('pending', 'processing', 'success', 'failed');--> statement-breakpoint
CREATE TYPE "public"."source_origin" AS ENUM('manual_input', 'upload', 'link');--> statement-breakpoint
CREATE TYPE "public"."source_type" AS ENUM('text', 'file', 'link');--> statement-breakpoint
CREATE TYPE "public"."tag_status" AS ENUM('active', 'archived');--> statement-breakpoint
CREATE TYPE "public"."upload_status" AS ENUM('initiated', 'uploaded', 'completed', 'expired');--> statement-breakpoint
CREATE TABLE "document_parse_jobs" (
	"id" uuid PRIMARY KEY NOT NULL,
	"document_id" uuid NOT NULL,
	"queue_job_id" varchar(128),
	"job_type" "job_type" NOT NULL,
	"status" "job_status" DEFAULT 'queued' NOT NULL,
	"attempt" integer DEFAULT 1 NOT NULL,
	"error_code" varchar(64),
	"error_message" text,
	"started_at" timestamp with time zone,
	"finished_at" timestamp with time zone,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "document_tags" (
	"document_id" uuid NOT NULL,
	"tag_id" uuid NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	CONSTRAINT "pk_document_tags" PRIMARY KEY("document_id","tag_id")
);
--> statement-breakpoint
CREATE TABLE "documents" (
	"id" uuid PRIMARY KEY NOT NULL,
	"owner_id" uuid,
	"title" varchar(255) NOT NULL,
	"content_raw" text,
	"content_clean" text,
	"content_preview" text,
	"search_text" text,
	"search_vector" "tsvector",
	"source_type" "source_type" NOT NULL,
	"source_origin" "source_origin" NOT NULL,
	"source_url" text,
	"file_name" text,
	"mime_type" varchar(255),
	"file_size" bigint,
	"object_key" text,
	"content_sha256" char(64),
	"parse_status" "parse_status" DEFAULT 'pending' NOT NULL,
	"parse_error_message" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"imported_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "tags" (
	"id" uuid PRIMARY KEY NOT NULL,
	"owner_id" uuid,
	"name" varchar(64) NOT NULL,
	"normalized_name" varchar(64) NOT NULL,
	"status" "tag_status" DEFAULT 'active' NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "uploads" (
	"id" uuid PRIMARY KEY NOT NULL,
	"owner_id" uuid,
	"file_name" text NOT NULL,
	"mime_type" varchar(255) NOT NULL,
	"file_size" bigint NOT NULL,
	"object_key" text NOT NULL,
	"checksum_sha256" char(64),
	"status" "upload_status" DEFAULT 'initiated' NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"completed_at" timestamp with time zone
);
--> statement-breakpoint
ALTER TABLE "document_parse_jobs" ADD CONSTRAINT "document_parse_jobs_document_id_documents_id_fk" FOREIGN KEY ("document_id") REFERENCES "public"."documents"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "document_tags" ADD CONSTRAINT "document_tags_document_id_documents_id_fk" FOREIGN KEY ("document_id") REFERENCES "public"."documents"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "document_tags" ADD CONSTRAINT "document_tags_tag_id_tags_id_fk" FOREIGN KEY ("tag_id") REFERENCES "public"."tags"("id") ON DELETE restrict ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "idx_document_parse_jobs_document_id" ON "document_parse_jobs" USING btree ("document_id");--> statement-breakpoint
CREATE INDEX "idx_document_parse_jobs_status" ON "document_parse_jobs" USING btree ("status");--> statement-breakpoint
CREATE INDEX "idx_document_tags_tag_id" ON "document_tags" USING btree ("tag_id");--> statement-breakpoint
CREATE INDEX "idx_documents_parse_status" ON "documents" USING btree ("parse_status");--> statement-breakpoint
CREATE INDEX "idx_documents_imported_at" ON "documents" USING btree ("imported_at");--> statement-breakpoint
CREATE INDEX "idx_documents_search_vector" ON "documents" USING gin ("search_vector");--> statement-breakpoint
CREATE INDEX "idx_documents_search_text_trgm" ON "documents" USING gin ("search_text" gin_trgm_ops);--> statement-breakpoint
CREATE UNIQUE INDEX "idx_tags_owner_normalized_name" ON "tags" USING btree ("owner_id","normalized_name") NULLS NOT DISTINCT;--> statement-breakpoint
CREATE INDEX "idx_uploads_status" ON "uploads" USING btree ("status");
