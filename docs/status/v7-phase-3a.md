# v7 / Phase 3A Status

## 1. Metadata

- `version`: `v7`
- `phase`: `Phase 3A`
- `status`: `in-progress`
- `owner`: `codex`
- `updated_at`: `2026-04-17`

## 2. Goal

### In Scope

- 在 `Phase 2C` 已完成的治理主板基线上，把 `v7` 收敛为深钻诊断与样本回放版本
- 明确 `drill-down / replay / release diff` 的问题定义、目标用户和验收标准
- 产出 `PRD / backlog / interaction delta / technical tradeoff` 第一版
- 产出 `architecture / data-model / api / contract-freeze-prerequisites` 第一版
- 产出 `Phase 3A Contract Freeze`
- 完成 `implementation-freeze`，形成代码开发 lane、写入边界和测试矩阵
- 将 Ask active-session stuck polling 纳入本轮 `P0-G1` 可靠性 guardrail，修复服务端终态收口、queue 对账和前端轮询兜底
- 更新 `handoff / status / current / AGENTS`，把默认恢复入口切到 `testing-and-release-readiness`

### Out Of Scope

- 在 `Lane 0 / Lane 0G` 完成前并行下放代码实现
- 未经显式版本分流与技术评估就修改 `schema / shared-types / API contract / citation / scope / eval`
- 自动 remediation、自动 rerun、自动回滚
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

- `now`: `Lane 0 / Lane 0G / Lane A / Lane B / Lane C / Lane D` 已完成；`v7 / Phase 3A` 进入 `testing-and-release-readiness`
- `next`: 运行 release-readiness 验证矩阵并检查 GitHub Actions；不得在 release gate 内扩大产品范围或修改 contract

### 4.1 P0 Guardrails

- `P0-G1`: Ask active-session 终态收口与轮询兜底
  - `status`: `implemented`
  - `reason`: Ask 页面可能因 answer session 长期停留 `retrieving / synthesizing` 而无限轮询，影响主问答链路和 Phase 3A replay 可信度
  - `outcome`: 未新增 `AnswerSessionStatus`；服务端读侧会将超时 active session 收口到 `failed`，Worker 会在 queue exhausted / 前置失败时尽量对账，Ask 页面增加最大轮询保护和 stuck 提示
  - `retrospective`: [Ask active session stuck polling retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-17-ask-active-session-stuck-polling-retrospective.md)

## 5. Blockers

- `blocker`: 无
  - `impact`: 无
  - `owner`: 无

## 6. Validation

- `latest_validation`: `2026-04-17` `Lane D` 本地验证已通过：`scripts/run-api-integration.sh`（20/20）、`scripts/run-e2e-smoke.sh`（8/8）、`pnpm --filter @xrag/web typecheck`、`pnpm --filter @xrag/web build`、`pnpm docs:check`、`pnpm contract:check`、`git diff --check`
- `result`: `passed`
- `latest_failure`: 无

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v7.md](/Users/coderlauu/xRag/docs/handoff/v7.md)
- `active_exec_plan`: [Phase 3A release readiness](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-17-phase-3a-release-readiness.md)
- `bug_retrospective`: [Ask active session stuck polling retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-17-ask-active-session-stuck-polling-retrospective.md)
- `completed_exec_plans`: [Phase 3A implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-17-phase-3a-implementation-lanes.md), [Phase 3A implementation freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-17-phase-3a-implementation-freeze.md), [Phase 3A contract freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-17-phase-3a-contract-freeze.md), [Phase 3A technical evaluation](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-17-phase-3a-technical-evaluation.md), [Phase 3A planning and scope](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-17-phase-3a-planning-and-scope.md)
- `prd`: [Phase 3A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-prd.md)
- `product_backlog`: [Phase 3A backlog](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-backlog.md)
- `interaction_delta`: [v7 interaction delta](/Users/coderlauu/xRag/design/spec/2026-04-17-v7-interaction-delta.md)
- `technical_tradeoffs`: [Phase 3A tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-17-phase-3a-p0-technical-tradeoffs.md)
- `architecture`: [Phase 3A architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-architecture.md)
- `contract_freeze`: [Phase 3A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md)
- `data_model`: [Phase 3A data model](/Users/coderlauu/xRag/tech/data-model/2026-04-17-phase-3a-data-model.md)
- `api_design`: [Phase 3A api design](/Users/coderlauu/xRag/tech/api/2026-04-17-phase-3a-api.md)
- `contract_freeze_prerequisites`: [Phase 3A contract freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-17-phase-3a-contract-freeze-prerequisites.md)
- `upstream_version`: [v6.md](/Users/coderlauu/xRag/docs/handoff/v6.md), [v6-phase-2c.md](/Users/coderlauu/xRag/docs/status/v6-phase-2c.md)
- `upstream_product_docs`: [Phase 2C PRD](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-prd.md), [Phase 2C backlog](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-backlog.md), [v6 interaction delta](/Users/coderlauu/xRag/design/spec/2026-04-16-v6-interaction-delta.md), [Phase 2C P0 technical tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-16-phase-2c-p0-technical-tradeoffs.md)
- `tech_docs`: [Phase 3A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md), [Phase 3A architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-architecture.md), [Phase 3A data model](/Users/coderlauu/xRag/tech/data-model/2026-04-17-phase-3a-data-model.md), [Phase 3A api design](/Users/coderlauu/xRag/tech/api/2026-04-17-phase-3a-api.md), [Phase 3A contract freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-17-phase-3a-contract-freeze-prerequisites.md), [Phase 2C contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-contract-freeze.md), [Phase 2A evaluation plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
- `latest_baseline_ci_run`: `24547237776 success (v7 contract-freeze / implementation-freeze baseline)`
