CREATE TYPE "public"."operator_recovery_action_status" AS ENUM('queued', 'running', 'succeeded', 'failed', 'cancelled', 'blocked');--> statement-breakpoint
CREATE TYPE "public"."operator_recovery_action_type" AS ENUM('document_reindex', 'document_retry', 'answer_diagnostic_rerun');--> statement-breakpoint
CREATE TYPE "public"."operator_recovery_target_type" AS ENUM('document', 'answer_session');--> statement-breakpoint
CREATE TABLE "operator_recovery_actions" (
	"id" uuid PRIMARY KEY NOT NULL,
	"candidate_id" varchar(255),
	"action_type" "operator_recovery_action_type" NOT NULL,
	"target_type" "operator_recovery_target_type" NOT NULL,
	"target_refs" jsonb NOT NULL,
	"status" "operator_recovery_action_status" DEFAULT 'queued' NOT NULL,
	"actor" varchar(255) NOT NULL,
	"reason" text NOT NULL,
	"idempotency_key" varchar(255) NOT NULL,
	"preview_id" varchar(255) NOT NULL,
	"source_facts" jsonb NOT NULL,
	"preview" jsonb NOT NULL,
	"before_facts" jsonb NOT NULL,
	"after_facts" jsonb,
	"queue_job_refs" jsonb,
	"diagnosis_code" varchar(64),
	"error_message" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"started_at" timestamp with time zone,
	"completed_at" timestamp with time zone,
	"updated_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE UNIQUE INDEX "idx_operator_recovery_actions_idempotency_key" ON "operator_recovery_actions" USING btree ("idempotency_key");--> statement-breakpoint
CREATE INDEX "idx_operator_recovery_actions_status_updated_at" ON "operator_recovery_actions" USING btree ("status","updated_at");--> statement-breakpoint
CREATE INDEX "idx_operator_recovery_actions_action_type_created_at" ON "operator_recovery_actions" USING btree ("action_type","created_at");