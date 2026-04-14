# Phase 2B Testing And Release Readiness

## 1. Metadata

- `plan_id`: `phase-2b-testing-and-release-readiness`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [v5 handoff](/Users/coderlauu/xRag/docs/handoff/v5.md), [v5 status](/Users/coderlauu/xRag/docs/status/v5-phase-2b.md), [current handoff](/Users/coderlauu/xRag/docs/handoff/current.md), [Phase 2B contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-13-phase-2b-contract-freeze.md), [Phase 2B implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-13-phase-2b-implementation-lanes.md)

## 2. Objective

在 `Lane 0 / A / B / C / D` 已全部落地后，把 `Phase 2B / P0` 从“特性实现完成”推进到“测试与发布准备完成”，补齐 answers integration、web e2e/smoke、必要的修复与状态收口。

## 3. Preconditions

当前已满足的前提：

1. `schema / migrations / shared-types / DTO / OpenAPI / SDK / web api adapter` 已冻结到代码事实源
2. `api` 已完成 `recent history / continue lineage / evidence_groups` read model
3. `worker` 已完成 `typed scope filters / answer_claims / claim-level citations / low_support exclusion / retrieval summary`
4. `web` 已完成 `ask workspace / recent history / continue asking / retrieval summary / search/detail jumpback`

本计划不重新打开 contract source-of-truth，仅在既有冻结边界内补测试与修缺口。

## 4. Workstreams

### Lane E: API Integration

- 目标：补齐 `answers history / retrieval summary / continue asking / evidence groups` 的 integration 覆盖
- 写入范围：
  - `apps/api/test/integration/**`
- 不得修改：
  - 生产代码 contract
- 最低验收：
  - `GET /api/v1/answers`
  - `GET /api/v1/answers/{id}`
  - `GET /api/v1/answers/{id}/retrieval`
  - `continued_from_session_id` 与 `evidence_groups` 的关键断言

### Lane F: Web E2E And Smoke

- 目标：补齐 ask/search/detail 的关键端到端路径，验证 recent history、continue asking、scope handoff 与 citation jumpback
- 写入范围：
  - `apps/web/e2e/**`
  - `apps/web/playwright.config.ts`
  - `scripts/run-e2e-smoke.sh`
- 不得修改：
  - 生产代码 contract
- 最低验收：
  - 从 `/search` 带 `search_result` 快照进入 `/ask`
  - 从 `/detail` 带 `document` scope 进入 `/ask`
  - `/ask` recent history 恢复会话并继续提问
  - claim evidence / retrieval item 跳回 `/detail` 锚点

### Release Readiness

- 目标：在测试通过后完成 `status / handoff / exec plan` 收口，并确认本地与 CI 基线
- 写入范围：
  - `docs/status/v5-phase-2b.md`
  - `docs/handoff/current.md`
  - `docs/handoff/v5.md`
  - `docs/exec-plans/**`
  - `AGENTS.md`
- 最低验收：
  - `Phase 2B` 状态更新到可发布或已完成
  - 活跃计划切到下一节点或归档
  - 新 session 仅靠 repo 文档即可恢复

## 5. Validation

- `git diff --check`
- `corepack pnpm docs:check`
- `pnpm --filter @xrag/api typecheck`
- `pnpm --filter @xrag/web typecheck`
- `pnpm test:integration`
- `pnpm test:e2e`
- `pnpm e2e:smoke`

如遇环境或 fixture 不足，可先以最小修复路径收口，并在 `status` 中明确缺失证据。

## 6. Risks

- 若 integration 只覆盖 happy path，`continued_from_session_id / retrieval summary / evidence_groups` 很容易在后续重构时退化
- 若 e2e 不覆盖 `/search -> /ask -> /detail` 的跳转链路，web 上的 scope handoff 与 citation jumpback 会长期缺少回归保护
- 若测试阶段重新修改 contract，会把原本已冻结的边界重新打散

## 7. Exit Criteria

当以下条件满足时，本计划可视为完成：

1. `Lane E / F` 已完成并合流
2. `Phase 2B / P0` 的 integration、e2e 与 smoke 基线已跑通或明确记录缺失项
3. `status / handoff / exec plan` 已切到 release-readiness 完成态或下一版本
4. 当前 repo 可在无聊天历史的情况下 resume

## 8. Decision Log

- `2026-04-14`: `Lane C / D` 已完成，`Phase 2B` feature lanes 退出条件满足
- `2026-04-14`: 原 `implementation-lanes` 计划归档，测试与发布准备切到独立 active exec plan
