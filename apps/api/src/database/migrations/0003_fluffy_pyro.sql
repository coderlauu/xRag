CREATE EXTENSION IF NOT EXISTS vector;--> statement-breakpoint
CREATE TYPE "public"."answer_scope_mode" AS ENUM('global', 'search_result', 'document');--> statement-breakpoint
CREATE TYPE "public"."answer_session_status" AS ENUM('idle', 'retrieving', 'synthesizing', 'answered', 'needs_scope', 'refused', 'failed');--> statement-breakpoint
CREATE TYPE "public"."index_status" AS ENUM('not_indexed', 'queued', 'chunking', 'embedding', 'ready', 'failed', 'stale');--> statement-breakpoint
CREATE TYPE "public"."retrieval_mode" AS ENUM('hybrid');--> statement-breakpoint
ALTER TYPE "public"."processing_event_stage" ADD VALUE IF NOT EXISTS 'index';--> statement-breakpoint
ALTER TYPE "public"."job_type" ADD VALUE IF NOT EXISTS 'chunk_document';--> statement-breakpoint
ALTER TYPE "public"."job_type" ADD VALUE IF NOT EXISTS 'embed_document';--> statement-breakpoint
CREATE TABLE "answer_citations" (
	"id" uuid PRIMARY KEY NOT NULL,
	"session_id" uuid NOT NULL,
	"document_id" uuid NOT NULL,
	"chunk_id" uuid NOT NULL,
	"claim_slot" varchar(64) NOT NULL,
	"quote_text" text NOT NULL,
	"locator" jsonb,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "answer_sessions" (
	"id" uuid PRIMARY KEY NOT NULL,
	"owner_id" uuid,
	"queue_job_id" varchar(128),
	"question" text NOT NULL,
	"scope_mode" "answer_scope_mode" NOT NULL,
	"scope_payload" jsonb,
	"retrieval_mode" "retrieval_mode" DEFAULT 'hybrid' NOT NULL,
	"status" "answer_session_status" DEFAULT 'idle' NOT NULL,
	"answer_summary" text,
	"refusal_reason" text,
	"diagnosis_code" varchar(64),
	"provider_name" varchar(64),
	"provider_model" varchar(128),
	"latency_ms" integer,
	"prompt_tokens" integer,
	"completion_tokens" integer,
	"total_cost_usd" numeric(10, 4),
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"finished_at" timestamp with time zone
);
--> statement-breakpoint
CREATE TABLE "document_chunks" (
	"id" uuid PRIMARY KEY NOT NULL,
	"document_id" uuid NOT NULL,
	"chunk_index" integer NOT NULL,
	"strategy_version" varchar(64) NOT NULL,
	"section_label" varchar(128),
	"page_ref" varchar(64),
	"content_text" text NOT NULL,
	"token_count" integer NOT NULL,
	"content_sha256" char(64) NOT NULL,
	"embedding" vector(1536),
	"citation_locator" jsonb,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "retrieval_run_hits" (
	"id" uuid PRIMARY KEY NOT NULL,
	"retrieval_run_id" uuid NOT NULL,
	"document_id" uuid NOT NULL,
	"chunk_id" uuid,
	"rank" integer NOT NULL,
	"lexical_score" numeric(10, 4),
	"semantic_score" numeric(10, 4),
	"final_score" numeric(10, 4),
	"used_in_answer" boolean DEFAULT false NOT NULL,
	"exclusion_reason" varchar(64),
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "retrieval_runs" (
	"id" uuid PRIMARY KEY NOT NULL,
	"session_id" uuid NOT NULL,
	"query_normalized" text NOT NULL,
	"eligible_document_count" integer NOT NULL,
	"lexical_hit_count" integer NOT NULL,
	"semantic_hit_count" integer NOT NULL,
	"merged_hit_count" integer NOT NULL,
	"rerank_strategy" varchar(64) DEFAULT 'hybrid' NOT NULL,
	"latency_ms" integer,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "index_status" "index_status" DEFAULT 'not_indexed' NOT NULL;--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "index_version" varchar(64);--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "indexed_at" timestamp with time zone;--> statement-breakpoint
ALTER TABLE "documents" ADD COLUMN "citation_ready" boolean DEFAULT false NOT NULL;--> statement-breakpoint
ALTER TABLE "answer_citations" ADD CONSTRAINT "answer_citations_session_id_answer_sessions_id_fk" FOREIGN KEY ("session_id") REFERENCES "public"."answer_sessions"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "answer_citations" ADD CONSTRAINT "answer_citations_document_id_documents_id_fk" FOREIGN KEY ("document_id") REFERENCES "public"."documents"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "answer_citations" ADD CONSTRAINT "answer_citations_chunk_id_document_chunks_id_fk" FOREIGN KEY ("chunk_id") REFERENCES "public"."document_chunks"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "document_chunks" ADD CONSTRAINT "document_chunks_document_id_documents_id_fk" FOREIGN KEY ("document_id") REFERENCES "public"."documents"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "retrieval_run_hits" ADD CONSTRAINT "retrieval_run_hits_retrieval_run_id_retrieval_runs_id_fk" FOREIGN KEY ("retrieval_run_id") REFERENCES "public"."retrieval_runs"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "retrieval_run_hits" ADD CONSTRAINT "retrieval_run_hits_document_id_documents_id_fk" FOREIGN KEY ("document_id") REFERENCES "public"."documents"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "retrieval_run_hits" ADD CONSTRAINT "retrieval_run_hits_chunk_id_document_chunks_id_fk" FOREIGN KEY ("chunk_id") REFERENCES "public"."document_chunks"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
ALTER TABLE "retrieval_runs" ADD CONSTRAINT "retrieval_runs_session_id_answer_sessions_id_fk" FOREIGN KEY ("session_id") REFERENCES "public"."answer_sessions"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "idx_answer_citations_session_id" ON "answer_citations" USING btree ("session_id");--> statement-breakpoint
CREATE INDEX "idx_answer_citations_document_id" ON "answer_citations" USING btree ("document_id");--> statement-breakpoint
CREATE INDEX "idx_answer_citations_chunk_id" ON "answer_citations" USING btree ("chunk_id");--> statement-breakpoint
CREATE INDEX "idx_answer_sessions_status" ON "answer_sessions" USING btree ("status");--> statement-breakpoint
CREATE INDEX "idx_answer_sessions_created_at" ON "answer_sessions" USING btree ("created_at");--> statement-breakpoint
CREATE INDEX "idx_document_chunks_document_id" ON "document_chunks" USING btree ("document_id");--> statement-breakpoint
CREATE UNIQUE INDEX "idx_document_chunks_document_chunk_index" ON "document_chunks" USING btree ("document_id","chunk_index");--> statement-breakpoint
CREATE INDEX "idx_retrieval_run_hits_retrieval_run_id" ON "retrieval_run_hits" USING btree ("retrieval_run_id");--> statement-breakpoint
CREATE INDEX "idx_retrieval_run_hits_document_id" ON "retrieval_run_hits" USING btree ("document_id");--> statement-breakpoint
CREATE INDEX "idx_retrieval_run_hits_rank" ON "retrieval_run_hits" USING btree ("rank");--> statement-breakpoint
CREATE INDEX "idx_retrieval_runs_session_id" ON "retrieval_runs" USING btree ("session_id");--> statement-breakpoint
CREATE INDEX "idx_retrieval_runs_created_at" ON "retrieval_runs" USING btree ("created_at");--> statement-breakpoint
CREATE INDEX "idx_documents_index_status" ON "documents" USING btree ("index_status");--> statement-breakpoint
