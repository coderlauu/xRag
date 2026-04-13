CREATE TABLE "answer_claims" (
	"id" uuid PRIMARY KEY NOT NULL,
	"session_id" uuid NOT NULL,
	"claim_slot" varchar(64) NOT NULL,
	"display_order" integer NOT NULL,
	"claim_text" text NOT NULL,
	"freshness_badge" varchar(32) NOT NULL,
	"created_at" timestamp with time zone DEFAULT now() NOT NULL
);
--> statement-breakpoint
ALTER TABLE "answer_sessions" ADD COLUMN "continued_from_session_id" uuid;--> statement-breakpoint
ALTER TABLE "answer_sessions" ADD COLUMN "updated_at" timestamp with time zone DEFAULT now() NOT NULL;--> statement-breakpoint
ALTER TABLE "answer_claims" ADD CONSTRAINT "answer_claims_session_id_answer_sessions_id_fk" FOREIGN KEY ("session_id") REFERENCES "public"."answer_sessions"("id") ON DELETE cascade ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "idx_answer_claims_session_display_order" ON "answer_claims" USING btree ("session_id","display_order");--> statement-breakpoint
CREATE UNIQUE INDEX "idx_answer_claims_session_claim_slot" ON "answer_claims" USING btree ("session_id","claim_slot");--> statement-breakpoint
ALTER TABLE "answer_sessions" ADD CONSTRAINT "answer_sessions_continued_from_session_id_answer_sessions_id_fk" FOREIGN KEY ("continued_from_session_id") REFERENCES "public"."answer_sessions"("id") ON DELETE set null ON UPDATE no action;--> statement-breakpoint
CREATE INDEX "idx_answer_sessions_continued_from_session_id" ON "answer_sessions" USING btree ("continued_from_session_id");