import { expect, test } from "@playwright/test";

const generatedAt = "2026-04-17T08:00:00.000Z";
const answerSample = {
  sample_id: "answer_session:e2e-answer-session",
  sample_kind: "answer_session",
  source_id: "e2e-answer-session",
  origin: "trend",
  severity: "high",
  detected_at: generatedAt,
  title: "E2E answer regression",
  summary: "Answer session lost retrieval scope after deployment.",
  related_incident_ref: "INC-E2E-ANSWER",
  related_deployment_record_id: "deploy-e2e-001",
  regression_class: "new_regression",
  next_replay_ref: {
    method: "GET",
    path: "/api/v1/ops/replays/answer-sessions/e2e-answer-session"
  }
} as const;
const documentSample = {
  sample_id: "document:e2e-document",
  sample_kind: "document_pipeline",
  source_id: "e2e-document",
  origin: "trend",
  severity: "medium",
  detected_at: generatedAt,
  title: "E2E document indexing block",
  summary: "Document pipeline has an embedding failure blocking citation readiness.",
  related_incident_ref: "INC-E2E-DOCUMENT",
  related_deployment_record_id: null,
  regression_class: "existing_debt",
  next_replay_ref: {
    method: "GET",
    path: "/api/v1/ops/replays/documents/e2e-document"
  }
} as const;

test("phase 3A ops diagnostic workflow opens samples, replays, and deployment compare", async ({ page }) => {
  await page.route("**/api/v1/ops/samples**", async (route) => {
    await route.fulfill({
      json: {
        generated_at: generatedAt,
        origin: "trend",
        window: "7d",
        page: 1,
        page_size: 8,
        total: 2,
        items: [answerSample, documentSample]
      }
    });
  });

  await page.route("**/api/v1/ops/replays/answer-sessions/e2e-answer-session", async (route) => {
    await route.fulfill({
      json: {
        generated_at: generatedAt,
        sample: answerSample,
        session: {
          session_id: "e2e-answer-session",
          question: "Why did replay fail?",
          scope: {
            mode: "global",
            payload: null
          },
          scope_summary: "全库",
          continued_from_session_id: null,
          status: "failed",
          answer_summary: null,
          refusal_reason: null,
          diagnosis_code: "provider_timeout",
          retrieval_mode: "hybrid",
          citations: [],
          evidence_groups: [],
          latency_ms: 5100,
          total_cost_usd: "0.2500",
          updated_at: generatedAt
        },
        retrieval: {
          session_id: "e2e-answer-session",
          summary: {
            query_normalized: "why did replay fail",
            eligible_document_count: 0,
            lexical_hit_count: 0,
            semantic_hit_count: 0,
            merged_hit_count: 0,
            rerank_strategy: "hybrid",
            latency_ms: 42
          },
          items: []
        },
        related_context: {
          related_incident_ref: "INC-E2E-ANSWER",
          related_deployment_record_id: "deploy-e2e-001",
          related_evaluation_run_ref: "eval-e2e-phase-3a",
          freshness_flags: ["retrieval_scope_empty"]
        }
      }
    });
  });

  await page.route("**/api/v1/ops/replays/documents/e2e-document", async (route) => {
    await route.fulfill({
      json: {
        generated_at: generatedAt,
        sample: documentSample,
        document: {
          id: "e2e-document",
          title: "E2E diagnostic document",
          content_preview: "Document replay preview",
          tags: ["e2e", "phase-3a"],
          source_type: "text",
          source_origin: "manual_input",
          source_url: null,
          file_name: null,
          parse_status: "success",
          index_status: "failed",
          indexed_at: null,
          citation_ready: false,
          ocr_status: null,
          upload_status: null,
          diagnosis_code: "index_embedding_failed",
          diagnosis_summary: "Embedding failed.",
          match_explanation: null,
          ranking_hint: null,
          matched_fields: null,
          latest_job_status: "failed",
          page_count: null,
          parser_name: null,
          imported_at: generatedAt,
          content_raw: "Raw document replay text",
          content_clean: "Clean document replay text",
          mime_type: "text/plain",
          parse_error_message: null,
          ocr_engine: null,
          ocr_language: null,
          upload: null,
          latest_job: {
            id: "e2e-job",
            status: "failed",
            diagnosis_code: "index_embedding_failed",
            finished_at: generatedAt
          },
          last_incident_ref: "INC-E2E-DOCUMENT",
          index_version: null,
          parser_version: null,
          created_at: generatedAt
        },
        timeline: {
          document_id: "e2e-document",
          items: [
            {
              event_type: "embedding_failed",
              stage: "index",
              status: "failed",
              diagnosis_code: "index_embedding_failed",
              summary: "Embedding provider failed.",
              created_at: generatedAt
            }
          ]
        },
        evidence: {
          document_id: "e2e-document",
          index_status: "failed",
          citation_ready: false,
          items: []
        },
        related_context: {
          blocking_reason: "indexing_failed",
          related_incident_ref: "INC-E2E-DOCUMENT",
          related_answer_session_count: 1,
          related_deployment_record_id: null
        }
      }
    });
  });

  await page.route("**/api/v1/ops/recovery/candidates**", async (route) => {
    await route.fulfill({
      json: {
        generated_at: generatedAt,
        page: 1,
        page_size: 6,
        total: 0,
        items: []
      }
    });
  });

  await page.route("**/api/v1/ops/deployments/compare**", async (route) => {
    await route.fulfill({
      json: {
        generated_at: generatedAt,
        deployment: {
          deployment_record_id: "deploy-e2e-001",
          environment: "production",
          commit_sha: "a".repeat(40),
          workflow_run_id: "24547000000",
          current_image_tag: "xrag-api:e2e-current",
          previous_stable_image_tag: "xrag-api:e2e-stable",
          smoke_status: "failed",
          smoke_at: generatedAt,
          deployed_at: generatedAt,
          evidence_url: "https://example.com/deploy/e2e"
        },
        baseline: {
          previous_stable_image_tag: "xrag-api:e2e-stable",
          previous_deployment_record_id: "deploy-e2e-000",
          related_evaluation_run_ref: "eval-e2e-phase-3a"
        },
        before_window: {
          start_at: "2026-04-17T07:00:00.000Z",
          end_at: generatedAt,
          sample_count: 0,
          high_severity_count: 0
        },
        after_window: {
          start_at: generatedAt,
          end_at: "2026-04-17T09:00:00.000Z",
          sample_count: 1,
          high_severity_count: 1
        },
        delta_summary: {
          regression_count: 1,
          new_regression_count: 1,
          existing_debt_count: 0,
          affected_answer_session_count: 1,
          affected_document_count: 0,
          summary: "1 new regression after deployment."
        },
        affected_samples: [answerSample]
      }
    });
  });

  await page.route("**/api/v1/ops/recovery/rollback-plan**", async (route) => {
    await route.fulfill({
      json: {
        generated_at: generatedAt,
        deployment_record_id: "deploy-e2e-001",
        compare_ref: {
          method: "GET",
          path: "/api/v1/ops/deployments/compare?deployment_record_id=deploy-e2e-001&window=7d"
        },
        affected_samples: [answerSample],
        quality_delta_summary: {
          regression_count: 1,
          new_regression_count: 1,
          existing_debt_count: 0,
          affected_answer_session_count: 1,
          affected_document_count: 0,
          summary: "1 new regression after deployment."
        },
        smoke_summary: "Smoke failed after deployment.",
        confidence: "medium",
        missing_evidence: ["缺少额外的 release evidence。"],
        manual_checklist: ["先检查 answer replay，再决定是否继续人工处置。"]
      }
    });
  });

  await page.goto("/ops");
  await expect(page.getByRole("heading", { name: "Diagnostics Workflow" })).toBeVisible();
  await expect(page.getByText("E2E answer regression")).toBeVisible();
  await expect(page.getByText("E2E document indexing block")).toBeVisible();

  await page
    .locator("article")
    .filter({ hasText: "E2E answer regression" })
    .getByRole("button", { name: "打开 replay" })
    .click();
  await expect(page.getByRole("heading", { name: "Answer replay" })).toBeVisible();
  await expect(page.getByText("Why did replay fail?")).toBeVisible();
  await expect(page.getByText("检索范围为空")).toBeVisible();

  await page
    .locator("article")
    .filter({ hasText: "E2E answer regression" })
    .getByRole("button", { name: "Compare deployment" })
    .click();
  const comparePanel = page.getByRole("heading", { name: "Deployment compare" }).locator("xpath=..");
  await expect(comparePanel).toBeVisible();
  await expect(comparePanel.getByText("xrag-api:e2e-current")).toBeVisible();
  await expect(comparePanel.getByText("1 new regression after deployment.", { exact: true })).toBeVisible();

  await page
    .locator("article")
    .filter({ hasText: "E2E document indexing block" })
    .getByRole("button", { name: "打开 replay" })
    .click();
  await expect(page.getByRole("heading", { name: "Document replay" })).toBeVisible();
  await expect(page.getByText("E2E diagnostic document")).toBeVisible();
  await expect(page.getByText("index_embedding_failed").first()).toBeVisible();
});
