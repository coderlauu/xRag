CREATE TYPE "public"."upload_mode" AS ENUM('single', 'multipart');--> statement-breakpoint
CREATE TYPE "public"."upload_part_status" AS ENUM('initiated', 'uploaded', 'failed');--> statement-breakpoint
CREATE TABLE "upload_parts" (
	"id" uuid PRIMARY KEY NOT NULL,
	"upload_id" uuid NOT NULL,
	"part_number" integer NOT NULL,
	"etag" varchar(255),
	"size_bytes" bigint,
	"status" "upload_part_status" DEFAULT 'initiated' NOT NULL,
	"error_code" varchar(64),
	"error_message" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
ALTER TABLE "uploads" ALTER COLUMN "status" SET DATA TYPE text;--> statement-breakpoint
ALTER TABLE "uploads" ALTER COLUMN "status" DROP DEFAULT;--> statement-breakpoint
UPDATE "uploads" SET "status" = 'uploaded' WHERE "status" = 'completed';--> statement-breakpoint
DROP TYPE "public"."upload_status";--> statement-breakpoint
CREATE TYPE "public"."upload_status" AS ENUM('draft', 'initiated', 'uploading', 'verifying', 'uploaded', 'failed', 'expired');--> statement-breakpoint
ALTER TABLE "uploads" ALTER COLUMN "status" SET DATA TYPE "public"."upload_status" USING "status"::"public"."upload_status";--> statement-breakpoint
ALTER TABLE "uploads" ALTER COLUMN "status" SET DEFAULT 'initiated'::"public"."upload_status";--> statement-breakpoint
ALTER TABLE "document_parse_jobs" ADD COLUMN "diagnosis_code" varchar(64);--> statement-breakpoint
ALTER TABLE "document_parse_jobs" ADD COLUMN "incident_ref" varchar(64);--> statement-breakpoint
ALTER TABLE "document_parse_jobs" ADD COLUMN "worker_name" varchar(64);--> statement-breakpoint
ALTER TABLE "document_parse_jobs" ADD COLUMN "runtime_ms" integer;--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "upload_status" "upload_status";--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "diagnosis_code" varchar(64);--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "diagnosis_summary" text;--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "upload_id" uuid;--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "page_count" integer;--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "parser_name" varchar(64);--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "parser_version" varchar(64);--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "last_incident_ref" varchar(64);--> statement-breakpoint
ALTER TABLE "uploads" ADD COLUMN "upload_mode" "upload_mode" DEFAULT 'single' NOT NULL;--> statement-breakpoint
ALTER TABLE "uploads" ADD COLUMN "provider_upload_id" varchar(255);--> statement-breakpoint
ALTER TABLE "uploads" ADD COLUMN "part_count" integer;--> statement-breakpoint
ALTER TABLE "uploads" ADD COLUMN "uploaded_part_count" integer DEFAULT 0 NOT NULL;--> statement-breakpoint
ALTER TABLE "uploads" ADD COLUMN "verified_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "uploads" ADD COLUMN "error_code" varchar(64);--> statement-breakpoint
ALTER TABLE "uploads" ADD COLUMN "error_message" text;--> statement-breakpoint
ALTER TABLE "uploads" ADD COLUMN "completed_by_client_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "upload_parts" ADD CONSTRAINT "upload_parts_upload_id_uploads_id_fk" FOREIGN KEY ("upload_id") REFERENCES "public"."uploads"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE UNIQUE INDEX "idx_upload_parts_upload_part_number" ON "upload_parts" USING btree ("upload_id","part_number");--> statement-breakpoint
CREATE INDEX "idx_document_parse_jobs_diagnosis_code" ON "document_parse_jobs" USING btree ("diagnosis_code");--> statement-breakpoint
CREATE INDEX "idx_documents_upload_status" ON "documents" USING btree ("upload_status");--> statement-breakpoint
CREATE INDEX "idx_documents_diagnosis_code" ON "documents" USING btree ("diagnosis_code");--> statement-breakpoint
CREATE INDEX "idx_uploads_provider_upload_id" ON "uploads" USING btree ("provider_upload_id");
