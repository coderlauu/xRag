# Phase 3B Release Readiness

## 1. Metadata

- `plan_id`: `phase-3b-release-readiness`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [v8 handoff](/Users/coderlauu/xRag/docs/handoff/v8.md), [v8 status](/Users/coderlauu/xRag/docs/status/v8-phase-3b.md), [Phase 3B implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-implementation-lanes.md), [Phase 3B contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-contract-freeze.md)

## 2. Objective

在 `Lane 0 / Lane A / Lane B / Lane C / Lane D` 全部完成后，进入 `testing-and-release-readiness`：确认 `v8 / Phase 3B` 没有 contract drift、关键验证矩阵稳定，并准备 closeout 所需的状态文档与 CI evidence。

## 3. Scope

### In Scope

- 运行并记录 `Phase 3B` release-readiness 验证矩阵
- 更新 `docs/status/v8-phase-3b.md`、`docs/handoff/current.md`、`docs/handoff/v8.md`、`AGENTS.md`
- 把 `Phase 3B implementation lanes` 从 `active` 移到 `completed`
- 提供稳定可复用的会话恢复 prompt 文件
- 如果本地验证通过，明确剩余外部闭环仅为 commit / GitHub Actions evidence

### Out Of Scope

- 新增 Phase 3B 产品能力
- 修改 API path、DTO、shared-types、OpenAPI、API client contract 或 schema
- 自动 remediation、自动 rollback、自动 rerun
- 把历史 `not_indexed` recovery 并入 v8 release scope

## 4. Validation Matrix

必须完成：

- `pnpm validate`
- `pnpm test:unit`
- `pnpm test:integration`
- `pnpm test:e2e`
- `pnpm e2e:smoke`

阶段性已完成：

- `pnpm test:integration`: passed, 23 tests
- `pnpm test:e2e`: passed, 10 tests
- `pnpm e2e:smoke`: passed, 10 tests
- Focused `e2e/phase-3b-ops-recovery.spec.ts`: passed, 2 tests
- `pnpm test:unit`: passed, 41 tests
- `pnpm harness:check`: passed
- `pnpm docs:check`: passed
- `git diff --check`: passed
- `pnpm validate`: 当前在 `pnpm contract:check` 因未提交的 generated OpenAPI diff 按脚本预期停止；不视为 Lane D 回归

## 5. Exit Criteria

1. 本地 release-readiness 验证矩阵通过，或明确记录不可运行项与原因。
2. 相关文档已同步到 `testing-and-release-readiness` 当前状态。
3. 相关 commit 已推送到 `main`。
4. 对应 GitHub Actions run 已成功。
5. 本计划移动到 `docs/exec-plans/completed/`。

## 6. Decision Log

- `2026-04-18`: `Phase 3B implementation-lanes` 完成并归档，下一步进入 release-readiness；当前不得再扩大 v8 scope。
- `2026-04-18`: closeout commit `0de02de` 推送至 main，GitHub Actions run `24602471351` 成功；所有 exit criteria 满足，本计划移至 completed。
