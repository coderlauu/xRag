# Phase 3A Release Readiness

## 1. Metadata

- `plan_id`: `phase-3a-release-readiness`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [v7 handoff](/Users/coderlauu/xRag/docs/handoff/v7.md), [v7 status](/Users/coderlauu/xRag/docs/status/v7-phase-3a.md), [Phase 3A implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-17-phase-3a-implementation-lanes.md), [Phase 3A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md)

## 2. Objective

在 `Lane 0 / Lane 0G / Lane A / Lane B / Lane C / Lane D` 全部完成后，进入 `testing-and-release-readiness`：确认 Phase 3A 没有 contract drift、构建和测试矩阵稳定、GitHub Actions 绿态，并完成 v7 收口所需的 handoff/status/exec-plan 归档准备。

当前计划已完成并归档；GitHub Actions run `24565067913` 已成功。

## 3. Scope

### In Scope

- 运行并记录 release-readiness 验证矩阵
- 检查 GitHub Actions 对应 main commit 的结果
- 更新 `docs/status/v7-phase-3a.md`、`docs/handoff/current.md`、`docs/handoff/v7.md` 与 `AGENTS.md`
- 如果验证全部通过，将本计划归档并准备 v7 closeout

### Out Of Scope

- 新增 Phase 3A 产品能力
- 修改 API path、DTO、shared-types、OpenAPI、API client contract 或 schema
- 自动 remediation、自动 rollback、自动 rerun
- 把历史 `not_indexed` recovery 并入 v7 release scope

## 4. Validation Matrix

必须完成：

- `pnpm validate`
- `pnpm test:unit`
- `pnpm test:integration`
- `pnpm test:e2e`
- `pnpm e2e:smoke`

阶段性已完成：

- `pnpm --filter @xrag/web typecheck`
- `pnpm --filter @xrag/web build`
- `scripts/run-api-integration.sh`
- `scripts/run-e2e-smoke.sh`
- `pnpm docs:check`
- `pnpm contract:check`
- `git diff --check`
- GitHub Actions run `24565067913`: `success`

## 5. Exit Criteria

1. 本地 release-readiness 验证矩阵通过，或明确记录不可运行项与原因。
2. 相关 commit 已推送到 `main`。
3. 对应 GitHub Actions run 已成功。
4. `v7 / Phase 3A` status 进入 release-ready 或 completed closeout 节点。
5. 本计划移动到 `docs/exec-plans/completed/`。

## 6. Decision Log

- `2026-04-17`: Phase 3A implementation lanes 已完成并归档，下一步进入 release-readiness；当前不得再扩大 v7 功能范围。
- `2026-04-17`: Release-readiness 验证完成，main commit `0b5f269` 对应 GitHub Actions run `24565067913` 已成功，v7 可进入 completed closeout。
