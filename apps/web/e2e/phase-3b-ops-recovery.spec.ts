import { expect, test, type Page } from "@playwright/test";

const generatedAt = "2026-04-18T12:00:00.000Z";
const deploymentRecordId = "deploy-e2e-phase-3b";
const documentSample = {
  sample_id: "document_pipeline:e2e-recovery-document",
  sample_kind: "document_pipeline",
  source_id: "e2e-recovery-document",
  origin: "trend",
  severity: "high",
  detected_at: generatedAt,
  title: "E2E recovery document backlog",
  summary: "Document replay shows citation readiness blocked after deployment.",
  related_incident_ref: "INC-E2E-RECOVERY-DOC",
  related_deployment_record_id: deploymentRecordId,
  regression_class: "new_regression",
  next_replay_ref: {
    method: "GET",
    path: "/api/v1/ops/replays/documents/e2e-recovery-document"
  }
} as const;
const answerSample = {
  sample_id: "answer_session:e2e-followup-answer",
  sample_kind: "answer_session",
  source_id: "e2e-followup-answer",
  origin: "trend",
  severity: "medium",
  detected_at: generatedAt,
  title: "E2E answer follow-up sample",
  summary: "Answer replay failed and should remain manual-only in Phase 3B.",
  related_incident_ref: "INC-E2E-RECOVERY-ANSWER",
  related_deployment_record_id: deploymentRecordId,
  regression_class: "existing_debt",
  next_replay_ref: {
    method: "GET",
    path: "/api/v1/ops/replays/answer-sessions/e2e-followup-answer"
  }
} as const;
const documentCandidate = {
  candidate_id: "candidate-document-reindex",
  source_type: "document_replay",
  source_ref: documentSample.source_id,
  action_type: "document_reindex",
  target_type: "document",
  target_refs: [{ type: "document", id: documentSample.source_id }],
  risk_level: "medium",
  recommendation_state: "recommended",
  title: "重建受影响文档索引",
  summary: "对当前文档执行受控 reindex，恢复 citation_ready。",
  preconditions: [
    {
      code: "document_exists",
      label: "文档存在",
      satisfied: true,
      detail: "document replay 已确认目标文档存在。"
    },
    {
      code: "no_active_reindex",
      label: "没有活跃 reindex",
      satisfied: true,
      detail: "当前没有正在运行的重建索引任务。"
    }
  ],
  blocked_reason: null,
  preview_ref: {
    method: "GET",
    path: "/api/v1/ops/recovery/actions/preview?candidate_id=candidate-document-reindex"
  }
} as const;
const answerCandidate = {
  candidate_id: "candidate-answer-followup",
  source_type: "answer_session_replay",
  source_ref: answerSample.source_id,
  action_type: "answer_diagnostic_rerun",
  target_type: "answer_session",
  target_refs: [{ type: "answer_session", id: answerSample.source_id }],
  risk_level: "high",
  recommendation_state: "blocked",
  title: "记录 answer diagnostic follow-up",
  summary: "Phase 3B 只记录人工 follow-up，不会自动替换用户可见 answer。",
  preconditions: [
    {
      code: "manual_only",
      label: "人工复核",
      satisfied: true,
      detail: "当前动作只允许记录人工 follow-up。"
    }
  ],
  blocked_reason: "Phase 3B keeps answer rerun manual-only.",
  preview_ref: {
    method: "GET",
    path: "/api/v1/ops/recovery/actions/preview?candidate_id=candidate-answer-followup"
  }
} as const;
const documentPreview = {
  preview_id: "preview-document-reindex",
  generated_at: generatedAt,
  expires_at: "2026-04-18T12:10:00.000Z",
  candidate_id: documentCandidate.candidate_id,
  action_type: "document_reindex",
  target_type: "document",
  target_refs: documentCandidate.target_refs,
  risk_level: "medium",
  recommendation_state: "recommended",
  preconditions: documentCandidate.preconditions,
  blocked_reason: null,
  estimated_blast_radius: "1 document",
  idempotency_key: "idem-document-reindex",
  source_facts: {
    captured_at: generatedAt,
    target_type: "document",
    target_refs: documentCandidate.target_refs,
    facts: {
      index_status: "failed",
      citation_ready: false,
      diagnosis_code: "index_embedding_failed"
    }
  },
  before_facts: {
    captured_at: generatedAt,
    target_type: "document",
    target_refs: documentCandidate.target_refs,
    facts: {
      latest_job_status: "failed",
      queue_depth: 0
    }
  }
} as const;
const answerPreview = {
  preview_id: "preview-answer-followup",
  generated_at: generatedAt,
  expires_at: "2026-04-18T12:10:00.000Z",
  candidate_id: answerCandidate.candidate_id,
  action_type: "answer_diagnostic_rerun",
  target_type: "answer_session",
  target_refs: answerCandidate.target_refs,
  risk_level: "high",
  recommendation_state: "blocked",
  preconditions: answerCandidate.preconditions,
  blocked_reason: "Phase 3B keeps answer rerun manual-only.",
  estimated_blast_radius: "1 answer session",
  idempotency_key: "idem-answer-followup",
  source_facts: {
    captured_at: generatedAt,
    target_type: "answer_session",
    target_refs: answerCandidate.target_refs,
    facts: {
      status: "failed",
      diagnosis_code: "provider_timeout"
    }
  },
  before_facts: {
    captured_at: generatedAt,
    target_type: "answer_session",
    target_refs: answerCandidate.target_refs,
    facts: {
      user_visible_answer_changed: false
    }
  }
} as const;
const documentAction = {
  action_id: "recovery-action-document",
  candidate_id: documentCandidate.candidate_id,
  status: "succeeded",
  action_type: "document_reindex",
  target_type: "document",
  target_refs: documentCandidate.target_refs,
  queue_job_refs: [{ method: "GET", path: "/api/v1/jobs/queue-reindex-document" }],
  diagnosis_code: null,
  error_message: null,
  created_at: generatedAt,
  started_at: generatedAt,
  completed_at: "2026-04-18T12:02:00.000Z",
  updated_at: "2026-04-18T12:02:00.000Z"
} as const;
const answerAction = {
  action_id: "recovery-action-answer",
  candidate_id: answerCandidate.candidate_id,
  status: "blocked",
  action_type: "answer_diagnostic_rerun",
  target_type: "answer_session",
  target_refs: answerCandidate.target_refs,
  queue_job_refs: [],
  diagnosis_code: "provider_timeout",
  error_message: "Phase 3B keeps answer rerun manual-only.",
  created_at: generatedAt,
  started_at: null,
  completed_at: generatedAt,
  updated_at: generatedAt
} as const;
const deploymentCompare = {
  generated_at: generatedAt,
  deployment: {
    deployment_record_id: deploymentRecordId,
    environment: "production",
    commit_sha: "e".repeat(40),
    workflow_run_id: "24599000001",
    current_image_tag: "xrag-api:phase3b-current",
    previous_stable_image_tag: "xrag-api:phase3b-stable",
    smoke_status: "failed",
    smoke_at: generatedAt,
    deployed_at: generatedAt,
    evidence_url: "https://example.com/deploy/phase3b"
  },
  baseline: {
    previous_stable_image_tag: "xrag-api:phase3b-stable",
    previous_deployment_record_id: "deploy-e2e-phase-3a",
    related_evaluation_run_ref: "eval-phase3b"
  },
  before_window: {
    start_at: "2026-04-18T10:00:00.000Z",
    end_at: generatedAt,
    sample_count: 0,
    high_severity_count: 0
  },
  after_window: {
    start_at: generatedAt,
    end_at: "2026-04-18T13:00:00.000Z",
    sample_count: 2,
    high_severity_count: 1
  },
  delta_summary: {
    regression_count: 1,
    new_regression_count: 1,
    existing_debt_count: 1,
    affected_answer_session_count: 1,
    affected_document_count: 1,
    summary: "1 suspected new regression and 1 existing debt surfaced after deployment."
  },
  affected_samples: [documentSample, answerSample]
} as const;
const rollbackPlan = {
  generated_at: generatedAt,
  deployment_record_id: deploymentRecordId,
  compare_ref: {
    method: "GET",
    path: `/api/v1/ops/deployments/compare?deployment_record_id=${deploymentRecordId}&window=7d`
  },
  affected_samples: [documentSample, answerSample],
  quality_delta_summary: deploymentCompare.delta_summary,
  smoke_summary: "Deploy smoke failed and 1 new regression emerged.",
  confidence: "medium",
  missing_evidence: ["缺少上一稳定版本的 answer replay 对比截图。"],
  manual_checklist: [
    "先在 document replay 复核 citation_ready 是否恢复。",
    "确认 answer incident 仍需人工跟进后再决定是否回滚。"
  ]
} as const;
const documentAudit = {
  generated_at: "2026-04-18T12:02:00.000Z",
  action: documentAction,
  actor: "operator:e2e",
  reason: "重建索引：已核对 before facts 与 deployment compare。",
  source_facts: documentPreview.source_facts,
  preview: documentPreview,
  before_facts: documentPreview.before_facts,
  after_facts: {
    captured_at: "2026-04-18T12:02:00.000Z",
    target_type: "document",
    target_refs: documentCandidate.target_refs,
    facts: {
      index_status: "ready",
      citation_ready: true
    }
  },
  status_timeline: [
    {
      status: "queued",
      at: generatedAt,
      summary: "Action created."
    },
    {
      status: "running",
      at: "2026-04-18T12:01:00.000Z",
      summary: "Document reindex queued job is running."
    },
    {
      status: "succeeded",
      at: "2026-04-18T12:02:00.000Z",
      summary: "Document replay now reports citation_ready."
    }
  ],
  manual_follow_up: ["Verify indexing health in document replay."]
} as const;
const answerAudit = {
  generated_at: generatedAt,
  action: answerAction,
  actor: "operator:e2e",
  reason: "人工跟进：记录 answer rerun 需要值班者手动复核。",
  source_facts: answerPreview.source_facts,
  preview: answerPreview,
  before_facts: answerPreview.before_facts,
  after_facts: null,
  status_timeline: [
    {
      status: "blocked",
      at: generatedAt,
      summary: "Answer rerun stays manual-only in Phase 3B."
    }
  ],
  manual_follow_up: ["Inspect the answer replay and coordinate manual operator follow-up."]
} as const;

test("phase 3B ops recovery workflow supports preview, confirm, audit, and rollback planning", async ({ page }) => {
  await mockPhase3bRecoveryApis(page);

  await page.goto("/ops");
  await expect(page.getByRole("heading", { name: "Diagnostics Workflow" })).toBeVisible();
  await expect(page.getByText(documentSample.title)).toBeVisible();

  await page
    .locator("article")
    .filter({ hasText: documentSample.title })
    .getByRole("button", { name: "打开 replay" })
    .click();

  await expect(page.getByRole("heading", { name: "Document replay" })).toBeVisible();
  await expect(page.getByText("E2E recovery document", { exact: true })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Recovery workflow" })).toBeVisible();

  const candidateCard = page.locator("article").filter({ hasText: documentCandidate.title }).first();
  await expect(candidateCard).toBeVisible();
  await candidateCard.getByRole("button", { name: "预览动作" }).click();

  const previewPanel = panelByHeading(page, "Preview / 确认");
  await expect(previewPanel.getByText("Blast radius: 1 document")).toBeVisible();
  const confirmButton = previewPanel.getByRole("button", { name: "确认并执行" });
  await expect(confirmButton).toBeDisabled();

  await previewPanel.locator("textarea").fill("重建索引：已核对 before facts 与 deployment compare。");
  await previewPanel.locator('input[type="checkbox"]').check();
  await expect(confirmButton).toBeEnabled();
  await confirmButton.click();

  const actionPanel = panelByHeading(page, "Action status / Audit");
  await expect(actionPanel.getByText(documentAction.action_id)).toBeVisible();
  await expect(actionPanel.getByText("已成功", { exact: true }).first()).toBeVisible();
  await expect(actionPanel.getByText("Verify indexing health in document replay.")).toBeVisible();

  await page
    .locator("article")
    .filter({ hasText: documentSample.title })
    .getByRole("button", { name: "Compare deployment" })
    .click();

  await expect(page.getByRole("heading", { name: "Rollback plan" })).toBeVisible();
  await expect(page.getByText("Guarded rollback plan")).toBeVisible();
  await expect(page.getByText("Deploy smoke failed and 1 new regression emerged.")).toBeVisible();
  await expect(page.getByText("缺少上一稳定版本的 answer replay 对比截图。")).toBeVisible();
  await expect(page.getByText("确认 answer incident 仍需人工跟进后再决定是否回滚。")).toBeVisible();
});

test("phase 3B answer diagnostic rerun stays read-only and records manual follow-up", async ({ page }) => {
  await mockPhase3bRecoveryApis(page);

  await page.goto("/ops");
  await expect(page.getByText(answerSample.title)).toBeVisible();

  await page
    .locator("article")
    .filter({ hasText: answerSample.title })
    .getByRole("button", { name: "打开 replay" })
    .click();

  await expect(page.getByRole("heading", { name: "Answer replay" })).toBeVisible();
  const candidateCard = page.locator("article").filter({ hasText: answerCandidate.title }).first();
  await expect(candidateCard).toBeVisible();
  await candidateCard.getByRole("button", { name: "预览动作" }).click();

  const previewPanel = panelByHeading(page, "Preview / 确认");
  await expect(previewPanel.getByText("只读 follow-up")).toBeVisible();
  const followUpButton = previewPanel.getByRole("button", { name: "记录人工跟进" });
  await expect(followUpButton).toBeDisabled();

  await previewPanel.locator("textarea").fill("人工跟进：记录 answer rerun 需要值班者手动复核。");
  await previewPanel.locator('input[type="checkbox"]').check();
  await expect(followUpButton).toBeEnabled();
  await followUpButton.click();

  const actionPanel = panelByHeading(page, "Action status / Audit");
  await expect(actionPanel.getByText(answerAction.action_id)).toBeVisible();
  await expect(actionPanel.getByText("已阻塞", { exact: true }).first()).toBeVisible();
  await expect(actionPanel.getByText("Inspect the answer replay and coordinate manual operator follow-up.")).toBeVisible();
});

async function mockPhase3bRecoveryApis(page: Page) {
  await page.route("**/api/v1/ops/overview", async (route) => {
    await route.fulfill({
      json: {
        generated_at: generatedAt,
        readiness: {
          queued_count: 0,
          chunking_count: 0,
          embedding_count: 0,
          ready_count: 12,
          stale_count: 0,
          failed_count: 1,
          total_count: 13,
          readiness_rate: 0.9231,
          freshness_lag_p95_ms: 1200,
          blocking_reason: "none"
        },
        runtime_quality: {
          window: "7d",
          terminal_session_count: 6,
          answered_session_count: 4,
          latency_p50_ms: 1200,
          latency_p95_ms: 3200,
          citation_coverage: 0.9,
          refusal_rate: 0.1,
          avg_token_cost_usd: "0.0200"
        },
        evaluation_quality: null,
        incident_summary: {
          open_count: 2,
          high_risk_count: 1,
          clusters: [
            {
              cluster_key: "retrieval:high:open",
              source: "deploy",
              severity: "high",
              status: "open",
              incident_count: 2,
              latest_incident_ref: "INC-E2E-RECOVERY-DOC",
              affected_surface: "deployment",
              recommended_action_code: "verify_latest_deployment"
            }
          ]
        },
        release_guard: {
          risk_level: "warning",
          current_image_tag: "xrag-api:phase3b-current",
          previous_stable_image_tag: "xrag-api:phase3b-stable",
          smoke_status: "failed",
          smoke_at: generatedAt,
          deployed_at: generatedAt,
          workflow_run_id: "24599000001",
          related_evaluation_run_ref: null,
          related_incident_count: 2,
          summary: "Deployment compare indicates guarded recovery work is required."
        },
        recommended_actions: [],
        notices: []
      }
    });
  });

  await page.route("**/api/v1/ops/trends**", async (route) => {
    await route.fulfill({
      json: {
        generated_at: generatedAt,
        window: "7d",
        series: []
      }
    });
  });

  await page.route("**/api/v1/ops/samples**", async (route) => {
    await route.fulfill({
      json: {
        generated_at: generatedAt,
        origin: "trend",
        window: "7d",
        page: 1,
        page_size: 8,
        total: 2,
        items: [documentSample, answerSample]
      }
    });
  });

  await page.route("**/api/v1/ops/replays/documents/e2e-recovery-document", async (route) => {
    await route.fulfill({
      json: {
        generated_at: generatedAt,
        sample: documentSample,
        document: {
          id: documentSample.source_id,
          title: "E2E recovery document",
          content_preview: "Recovery document preview",
          tags: ["e2e", "phase-3b"],
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
          diagnosis_summary: "Embedding worker failed after deployment.",
          match_explanation: null,
          ranking_hint: null,
          matched_fields: null,
          latest_job_status: "failed",
          page_count: null,
          parser_name: null,
          imported_at: generatedAt,
          content_raw: "raw recovery content",
          content_clean: "clean recovery content",
          mime_type: "text/plain",
          parse_error_message: null,
          ocr_engine: null,
          ocr_language: null,
          upload: null,
          latest_job: {
            id: "job-document-recovery",
            status: "failed",
            diagnosis_code: "index_embedding_failed",
            finished_at: generatedAt
          },
          last_incident_ref: documentSample.related_incident_ref,
          index_version: null,
          parser_version: null,
          created_at: generatedAt
        },
        timeline: {
          document_id: documentSample.source_id,
          items: [
            {
              event_type: "embedding_failed",
              stage: "index",
              status: "failed",
              diagnosis_code: "index_embedding_failed",
              summary: "Embedding worker failed.",
              created_at: generatedAt
            }
          ]
        },
        evidence: {
          document_id: documentSample.source_id,
          index_status: "failed",
          citation_ready: false,
          items: []
        },
        related_context: {
          blocking_reason: "indexing_failed",
          related_incident_ref: documentSample.related_incident_ref,
          related_answer_session_count: 1,
          related_deployment_record_id: deploymentRecordId
        }
      }
    });
  });

  await page.route("**/api/v1/ops/replays/answer-sessions/e2e-followup-answer", async (route) => {
    await route.fulfill({
      json: {
        generated_at: generatedAt,
        sample: answerSample,
        session: {
          session_id: answerSample.source_id,
          question: "Why should this stay manual?",
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
          latency_ms: 4100,
          total_cost_usd: "0.1200",
          updated_at: generatedAt
        },
        retrieval: {
          session_id: answerSample.source_id,
          summary: {
            query_normalized: "why should this stay manual",
            eligible_document_count: 0,
            lexical_hit_count: 0,
            semantic_hit_count: 0,
            merged_hit_count: 0,
            rerank_strategy: "hybrid",
            latency_ms: 35
          },
          items: []
        },
        related_context: {
          related_incident_ref: answerSample.related_incident_ref,
          related_deployment_record_id: deploymentRecordId,
          related_evaluation_run_ref: null,
          freshness_flags: ["retrieval_scope_empty"]
        }
      }
    });
  });

  await page.route("**/api/v1/ops/recovery/candidates**", async (route) => {
    const url = new URL(route.request().url());
    const sourceRef = url.searchParams.get("source_ref");

    await route.fulfill({
      json: {
        generated_at: generatedAt,
        page: 1,
        page_size: 6,
        total: 1,
        items: sourceRef === answerSample.source_id ? [answerCandidate] : [documentCandidate]
      }
    });
  });

  await page.route("**/api/v1/ops/recovery/actions/preview", async (route) => {
    const body = route.request().postDataJSON() as { candidate_id: string };

    await route.fulfill({
      json: body.candidate_id === answerCandidate.candidate_id ? answerPreview : documentPreview
    });
  });

  await page.route("**/api/v1/ops/recovery/actions/recovery-action-document/audit", async (route) => {
    await route.fulfill({ json: documentAudit });
  });

  await page.route("**/api/v1/ops/recovery/actions/recovery-action-answer/audit", async (route) => {
    await route.fulfill({ json: answerAudit });
  });

  await page.route("**/api/v1/ops/recovery/actions/recovery-action-document", async (route) => {
    await route.fulfill({ json: documentAction });
  });

  await page.route("**/api/v1/ops/recovery/actions/recovery-action-answer", async (route) => {
    await route.fulfill({ json: answerAction });
  });

  await page.route("**/api/v1/ops/recovery/actions", async (route) => {
    const body = route.request().postDataJSON() as { candidate_id: string; reason: string };

    if (body.candidate_id === documentCandidate.candidate_id) {
      expect(body.reason).toContain("重建索引");
      await route.fulfill({ json: documentAction });
      return;
    }

    expect(body.reason).toContain("人工跟进");
    await route.fulfill({ json: answerAction });
  });

  await page.route("**/api/v1/ops/deployments/compare**", async (route) => {
    await route.fulfill({ json: deploymentCompare });
  });

  await page.route("**/api/v1/ops/recovery/rollback-plan**", async (route) => {
    await route.fulfill({ json: rollbackPlan });
  });
}

function panelByHeading(page: Page, heading: string) {
  return page.getByRole("heading", { name: heading }).locator("xpath=..");
}
