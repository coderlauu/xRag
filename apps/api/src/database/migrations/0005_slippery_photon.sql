CREATE TYPE "public"."deployment_smoke_status" AS ENUM('passed', 'failed', 'unknown');--> statement-breakpoint
CREATE TYPE "public"."evaluation_run_status" AS ENUM('running', 'completed', 'failed');--> statement-breakpoint
CREATE TABLE "deployment_records" (
	"id" uuid PRIMARY KEY NOT NULL,
	"environment" varchar(32) NOT NULL,
	"commit_sha" char(40),
	"workflow_run_id" varchar(32),
	"current_image_tag" text NOT NULL,
	"previous_stable_image_tag" text,
	"smoke_status" "deployment_smoke_status" DEFAULT 'unknown' NOT NULL,
	"smoke_at" timestamp with time zone,
	"deployed_at" timestamp with time zone NOT NULL,
	"evidence_url" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
CREATE TABLE "evaluation_runs" (
	"id" uuid PRIMARY KEY NOT NULL,
	"run_ref" varchar(64) NOT NULL,
	"environment" varchar(32) NOT NULL,
	"source" varchar(32) NOT NULL,
	"status" "evaluation_run_status" DEFAULT 'running' NOT NULL,
	"commit_sha" char(40),
	"dataset_version" varchar(64),
	"recall_at_10" numeric(6, 4),
	"mrr" numeric(6, 4),
	"hit_in_answer_rate" numeric(6, 4),
	"groundedness" numeric(6, 4),
	"citation_coverage" numeric(6, 4),
	"refusal_precision" numeric(6, 4),
	"latency_p95_ms" integer,
	"avg_token_cost_usd" numeric(12, 4),
	"embedding_backlog" integer,
	"freshness_lag_p95_ms" integer,
	"artifact_url" text,
	"error_message" text,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL,
	"completed_at" timestamp with time zone
);
--> statement-breakpoint
CREATE INDEX "idx_deployment_records_environment_deployed_at" ON "deployment_records" USING btree ("environment","deployed_at");--> statement-breakpoint
CREATE INDEX "idx_deployment_records_commit_sha" ON "deployment_records" USING btree ("commit_sha");--> statement-breakpoint
CREATE INDEX "idx_deployment_records_workflow_run_id" ON "deployment_records" USING btree ("workflow_run_id");--> statement-breakpoint
CREATE UNIQUE INDEX "idx_evaluation_runs_run_ref" ON "evaluation_runs" USING btree ("run_ref");--> statement-breakpoint
CREATE INDEX "idx_evaluation_runs_environment_completed_at" ON "evaluation_runs" USING btree ("environment","completed_at");--> statement-breakpoint
CREATE INDEX "idx_evaluation_runs_commit_sha" ON "evaluation_runs" USING btree ("commit_sha");--> statement-breakpoint
CREATE INDEX "idx_evaluation_runs_status" ON "evaluation_runs" USING btree ("status");