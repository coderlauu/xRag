# v8 / Phase 3B Status

## 1. Metadata

- `version`: `v8`
- `phase`: `Phase 3B`
- `status`: `in-progress`
- `owner`: `codex`
- `updated_at`: `2026-04-18`

## 2. Goal

### In Scope

- 在 `v7 / Phase 3A` 只读诊断 workflow 基线上，规划 human-in-the-loop ops recovery。
- 将 recovery 候选、人工确认 rerun、执行审计与 rollback plan 收敛为 `v8 / P0` 范围。
- 已产出 planning-and-scope 结论、PRD、backlog、interaction delta、technical tradeoff、architecture、data model、API design、contract freeze prerequisites、正式 contract freeze、implementation freeze 与 Lane 0 contract-to-code。
- 已完成 `Lane B: API Recovery Action Execution And Reconciliation`、`Lane C: Web Ops Recovery Workflow` 与 `Lane D: Integration, E2E, Smoke`；当前已补齐 recovery integration、Web E2E 与 smoke 覆盖，并切入 `testing-and-release-readiness`。
- 对任何 API、schema、shared-types、状态机、OpenAPI 或 API client 变化使用 `contract-guard`。
- 对任何 retrieval、answer、citation、freshness、refusal 或 eval 变化使用 `answer-quality-gate`。

### Out Of Scope

- 继续扩大 `v7 / Phase 3A` scope
- 未经 `contract-guard` 回写冻结文档直接修改 Lane 0 已落地的 schema、shared-types、DTO、OpenAPI、API client 或状态枚举
- 未经 contract freeze 修改 schema、shared-types、DTO、OpenAPI、API client 或状态枚举
- 全自动 remediation、全自动 rerun、全自动 rollback
- 多模型治理、团队协作权限、浏览器插件、移动端、开放互联网联网回答
- 把 production 历史 `not_indexed` 回补直接并入 v8 P0；该事项应先作为版本外 dry-run/runbook 处理

## 3. Milestones

- `planning-and-scope`: `completed`
- `product-freeze`: `completed`
- `technical-evaluation`: `completed`
- `contract-freeze`: `completed`
- `implementation-freeze`: `completed`
- `implementation-lanes`: `completed`
- `testing-and-release-readiness`: `in-progress`

## 4. Current Node

- `now`: `v8 / Phase 3B` 已完成 planning-and-scope、product freeze、technical-evaluation、contract-freeze、implementation-freeze、`Lane 0: Contract To Code`、`Lane A: API Recovery Candidates, Preview, Rollback Plan`、`Lane B: API Recovery Action Execution And Reconciliation`、`Lane C: Web Ops Recovery Workflow` 与 `Lane D: Integration, E2E, Smoke`；implementation-lanes 已完成，当前 active exec plan 已切到 `testing-and-release-readiness`。`v7 / Phase 3A` 已作为完成基线归档，不再扩 scope。
- `next`: 在不修改已冻结 recovery contract surfaces 的前提下完成剩余 release-readiness 验证、文档收口与 CI evidence 准备。

## 5. Blockers

- `blocker`: 无
  - `impact`: 无
  - `owner`: 无

## 6. Validation

- `latest_validation`: `2026-04-18` Lane D 与 release-readiness 当前已通过 `pnpm test:integration`（23 tests）、Focused `e2e/phase-3b-ops-recovery.spec.ts`（2 tests）、`pnpm test:e2e`（10 tests）、`pnpm e2e:smoke`（10 tests）、`pnpm test:unit`（41 tests）、`pnpm harness:check`、`pnpm docs:check` 与 `git diff --check`；`pnpm validate` 当前在 `pnpm contract:check` 因未提交的 generated OpenAPI diff 按脚本预期停止，不视为 Lane D / release-readiness 回归。此前 Lane C 已通过 `pnpm --filter @xrag/web typecheck`，Lane B 已通过 `pnpm --filter @xrag/api typecheck`、`pnpm --filter @xrag/api build:test` 与 `node --test --test-concurrency=1 apps/api/dist-integration/apps/api/test/integration/ops.phase-3b.integration.test.js`（3 tests passed），Lane 0 已通过 `pnpm --filter @xrag/shared-types typecheck`、`pnpm --filter @xrag/api-client typecheck`、`pnpm --filter @xrag/web typecheck` 与 `pnpm --filter @xrag/api openapi:generate`。
- `result`: `passed`
- `latest_failure`: 无

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v8.md](/Users/coderlauu/xRag/docs/handoff/v8.md)
- `active_exec_plan`: [Phase 3B release readiness](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-18-phase-3b-release-readiness.md)
- `latest_completed_exec_plan`: [Phase 3B implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-implementation-lanes.md)
- `prd`: [Phase 3B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-prd.md)
- `product_backlog`: [Phase 3B backlog](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-backlog.md)
- `interaction_delta`: [v8 interaction delta](/Users/coderlauu/xRag/design/spec/2026-04-18-v8-interaction-delta.md)
- `technical_tradeoffs`: [Phase 3B tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-18-phase-3b-p0-technical-tradeoffs.md)
- `upstream_version`: [v7.md](/Users/coderlauu/xRag/docs/handoff/v7.md), [v7-phase-3a.md](/Users/coderlauu/xRag/docs/status/v7-phase-3a.md)
- `upstream_release_readiness`: [Phase 3A release readiness](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-17-phase-3a-release-readiness.md)
- `bug_retrospective`: [Ask active session stuck polling retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-17-ask-active-session-stuck-polling-retrospective.md)
- `upstream_contract`: [Phase 3A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md)
- `upstream_api`: [Phase 3A API design](/Users/coderlauu/xRag/tech/api/2026-04-17-phase-3a-api.md)
- `upstream_data_model`: [Phase 3A data model](/Users/coderlauu/xRag/tech/data-model/2026-04-17-phase-3a-data-model.md)
- `architecture`: [Phase 3B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-architecture.md)
- `data_model`: [Phase 3B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-18-phase-3b-data-model.md)
- `api_design`: [Phase 3B API design](/Users/coderlauu/xRag/tech/api/2026-04-18-phase-3b-api.md)
- `contract_freeze`: [Phase 3B contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-contract-freeze.md)
- `contract_freeze_prerequisites`: [Phase 3B contract freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-18-phase-3b-contract-freeze-prerequisites.md)
- `tech_docs`: [Phase 3B contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-contract-freeze.md), [Phase 3B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-architecture.md), [Phase 3B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-18-phase-3b-data-model.md), [Phase 3B API design](/Users/coderlauu/xRag/tech/api/2026-04-18-phase-3b-api.md), [Phase 3B contract freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-18-phase-3b-contract-freeze-prerequisites.md)
- `exec_plans`: [Phase 3B release readiness](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-18-phase-3b-release-readiness.md), [Phase 3B implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-implementation-lanes.md), [Phase 3B implementation freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-implementation-freeze.md), [Phase 3B contract freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-contract-freeze.md), [Phase 3B technical evaluation](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-technical-evaluation.md), [Phase 3B planning and scope](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-planning-and-scope.md)
- `key_commits`: `991725c` 为 v7 docs closeout 当前 main baseline
- `latest_ci_run`: `24565067913 success (v7 Phase 3A release-readiness baseline)`
