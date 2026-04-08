# v4 / Phase 2A Status

## 1. Metadata

- `version`: `v4`
- `phase`: `Phase 2A`
- `status`: `in-progress`
- `owner`: `codex`
- `updated_at`: `2026-04-08`

## 2. Goal

### In Scope

- 在统一导入、检索与可观测性基线上，引入可引用的 AI 问答与混合检索，让资料从可找回走向可直接复用。
- 基于个人知识库的 `AI 问答`
- `keyword + semantic` 混合检索
- 问答范围控制
- 答案证据链与无证据拒答
- 导入内容到向量索引的 freshness、诊断与回补基线
- 检索 / 问答质量、延迟与成本的最小评估框架

### Out Of Scope

- 开放互联网联网回答
- 团队协作 / 权限模型
- 浏览器插件
- 移动端
- 全自动 agent 工作流
- 更大范围的动态页面抓取和反反爬能力

## 3. Milestones

- `foundation`: `completed`
- `api-persistence`: `not-started`
- `upload-storage`: `not-started`
- `worker-pipeline`: `not-started`
- `web-integration`: `not-started`
- `testing`: `not-started`
- `ci-cd`: `not-started`

## 4. Current Node

- `now`: `Phase 2A / P0` 已完成 implementation freeze，并正式切换到 implementation lanes；当前阶段已允许进入代码开发，但仍遵守 `main thread first`，先由主线程把 `schema / shared-types / API contract / 状态机 / citation / scope` 落到代码事实源
- `next`: 主线程先完成 `Lane 0` 的 `schema + migration + documents / answers / ops contract skeleton + shared-types + OpenAPI / SDK`，随后再并行启动 `worker-runtime / provider / ask workspace` 等子 lane

## 5. Blockers

- `blocker`: 无
  - `impact`: 无
  - `owner`: 无

## 6. Validation

- `latest_validation`: `2026-04-08` 已完成 `PRD / backlog / handoff / status / implementation-freeze exec plan / implementation-lanes exec plan / interaction spec / prototype / evaluation plan / technical tradeoffs / runtime contracts / contract freeze / data model / api / implementation freeze prerequisites` 的交叉核对；完成 `@xrag/api` 与 `@xrag/worker` 的 `typecheck`；完成本地 compose 级 `CREATE EXTENSION vector` 验证，并通过 `docker compose config`、`deploy stack compose config`、`git diff --check` 与 `corepack pnpm docs:check`
- `result`: `passed`
- `latest_failure`: 无

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v4.md](/Users/coderlauu/xRag/docs/handoff/v4.md)
- `prd`: [Phase 2A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-prd.md)
- `product_backlog`: [Phase 2A backlog](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-backlog.md)
- `technical_decisions`: [Phase 2A technical tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-p0-technical-tradeoffs.md)
- `freeze_prerequisites`: [Phase 2A implementation freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-implementation-freeze-prerequisites.md)
- `runtime_contracts`: [Phase 2A runtime contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)
- `contract_freeze`: [Phase 2A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md)
- `tech_docs`: [Phase 2A architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-2a-architecture.md), [Phase 2A runtime contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md), [Phase 2A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md), [Phase 2A data model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-2a-data-model.md), [Phase 2A api design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-2a-api.md), [Phase 1C architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-1c-architecture.md), [Phase 1C data model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-1c-data-model.md), [Phase 1C api design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-1c-api.md)
- `prototype`: [prototype/v4](/Users/coderlauu/xRag/prototype/v4/index.html)
- `interaction_spec`: [v4 interaction spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v4-interaction-spec.md)
- `exec_plans`: [Phase 2A implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-08-phase-2a-implementation-lanes.md), [Phase 2A implementation freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-08-phase-2a-implementation-freeze.md), [Phase 2A planning and design](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-07-phase-2a-planning-and-design.md), [Phase 1C planning and design](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-07-phase-1c-planning-and-design.md), [Phase 1C implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-07-phase-1c-implementation-lanes.md)
- `evaluation_plan`: [Phase 2A evaluation plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
- `generated_contract`: [Phase 1C OpenAPI](/Users/coderlauu/xRag/docs/generated/openapi/phase-1c-api.json)（`Phase 2A` 尚未进入 contract 生成节点）
- `ops_runbook`: [deploy README](/Users/coderlauu/xRag/deploy/README.md), [production inspection guide](/Users/coderlauu/xRag/deploy/production-inspection-guide.md)
- `key_commits`: `待补充`
- `latest_ci_run`: `沿用 v3 基线 24081424992`
