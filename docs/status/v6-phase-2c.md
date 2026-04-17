# v6 / Phase 2C Status

## 1. Metadata

- `version`: `v6`
- `phase`: `Phase 2C`
- `status`: `in-progress`
- `owner`: `codex`
- `updated_at`: `2026-04-17`

## 2. Goal

### In Scope

- 在 `Phase 2B` 已完成的可信问答基线上，把 `v6` 收敛为质量治理与运行治理版本。
- 以 `PRD / backlog / interaction delta / technical tradeoff` 为准冻结 `v6 / P0`。
- 完成 `implementation-freeze`，并进入 `implementation-lanes`。

### Out Of Scope

- 绕过 `Lane 0` 直接启动并行实现 lane
- 未经显式 contract 变更流程就重开 `Phase 2A / 2B` 已冻结边界
- 默认把 production `not_indexed` 回补当成 `v6` 产品范围
- 开放互联网联网回答
- 团队协作 / 权限模型
- 浏览器插件
- 移动端

## 3. Milestones

- `planning-and-scope`: `completed`
- `product-freeze`: `completed`
- `technical-evaluation`: `completed`
- `contract-freeze`: `completed`
- `implementation-freeze`: `completed`
- `implementation-lanes`: `completed`
- `testing-and-release-readiness`: `in-progress`

## 4. Current Node

- `now`: `v6 / Phase 2C` 已完成 `Lane 0 / A / B / C / D` 全部实现与测试 lane，当前已切到 `testing-and-release-readiness`；`/ops` 治理主板、prompt-only notices、integration 与 e2e/smoke 本地证据均已补齐。
- `next`: 确认当前 main HEAD 的 GitHub Actions 结论；若 CI 绿灯，则直接完成 `Phase 2C` 收口并归档当前 active exec plan，否则把 blocker 与待确认证据明确写回 status/handoff。

## 5. Blockers

- `blocker`: 无
  - `impact`: 无
  - `owner`: 无

## 6. Validation

- `latest_validation`: `2026-04-17` 已完成 `Phase 2C Lane D` 测试收口；通过 `git diff --check`、`pnpm docs:check`、`pnpm test:integration`、`./scripts/run-e2e-smoke.sh`
- `result`: `passed`
- `latest_failure`: `pnpm test:integration` 曾因 `apps/worker/src/queue/constants.ts` 运行期 value re-export 触发 Node 直接加载 `@xrag/shared-types` 源码 TS 而失败；已改为 worker 本地运行期常量定义并用 shared-types union type 约束，重跑 `pnpm test:integration` 通过 `16/16`

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v6.md](/Users/coderlauu/xRag/docs/handoff/v6.md)
- `active_exec_plan`: [Phase 2C testing and release readiness](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-17-phase-2c-testing-and-release-readiness.md)
- `prd`: [Phase 2C PRD](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-prd.md)
- `product_backlog`: [Phase 2C backlog](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-backlog.md)
- `interaction_delta`: [v6 interaction delta](/Users/coderlauu/xRag/design/spec/2026-04-16-v6-interaction-delta.md)
- `technical_tradeoffs`: [Phase 2C tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-16-phase-2c-p0-technical-tradeoffs.md)
- `architecture`: [Phase 2C architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-architecture.md)
- `data_model`: [Phase 2C data model](/Users/coderlauu/xRag/tech/data-model/2026-04-16-phase-2c-data-model.md)
- `api_design`: [Phase 2C API design](/Users/coderlauu/xRag/tech/api/2026-04-16-phase-2c-api.md)
- `contract_freeze_prerequisites`: [Phase 2C contract freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-16-phase-2c-contract-freeze-prerequisites.md)
- `contract_freeze`: [Phase 2C contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-contract-freeze.md)
- `upstream_version`: [v5.md](/Users/coderlauu/xRag/docs/handoff/v5.md), [v5-phase-2b.md](/Users/coderlauu/xRag/docs/status/v5-phase-2b.md)
- `upstream_product_docs`: [Phase 2B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-prd.md), [Phase 2B backlog](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-backlog.md), [v5 interaction delta](/Users/coderlauu/xRag/design/spec/2026-04-11-v5-interaction-delta.md), [Phase 2B P0 technical tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-11-phase-2b-p0-technical-tradeoffs.md)
- `tech_docs`: [Phase 2B contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-13-phase-2b-contract-freeze.md), [Phase 2A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md)
- `exec_plans`: [Phase 2C testing and release readiness](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-17-phase-2c-testing-and-release-readiness.md), [Phase 2C implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-16-phase-2c-implementation-lanes.md), [Phase 2C implementation freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-16-phase-2c-implementation-freeze.md), [Phase 2C contract freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-16-phase-2c-contract-freeze.md), [Phase 2C technical evaluation](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-16-phase-2c-technical-evaluation.md), [Phase 2C planning and scope](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-16-phase-2c-planning-and-scope.md), [Phase 2B testing and release readiness](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-14-phase-2b-testing-and-release-readiness.md)
- `key_commits`: `902fda5`, `5bb983f`, `a5a0965`, `8e35abc`, `4417bb7`, `30e5a88`, `bb5def7`, `169799a`, `cefbbf7`
- `latest_ci_run`: `24514690725 success`
