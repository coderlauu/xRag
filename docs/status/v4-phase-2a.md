# v4 / Phase 2A Status

## 1. Metadata

- `version`: `v4`
- `phase`: `Phase 2A`
- `status`: `in-progress`
- `owner`: `codex`
- `updated_at`: `2026-04-09`

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
- `api-persistence`: `in-progress`
- `upload-storage`: `not-started`
- `worker-pipeline`: `completed`
- `web-integration`: `in-progress`
- `testing`: `not-started`
- `ci-cd`: `not-started`

## 4. Current Node

- `now`: `Phase 2A / P0` 已完成 `Lane 0`、`Lane A`、`Lane B`、`Lane C`、`Lane D`、`Lane E` 与 `Lane G`；`document indexing`、`hybrid retrieval`、`citation persistence`、`answer orchestration` 与 `ops answer summary` 已接入主链。当前临时插入一条 production ops hardening 支线，补 deploy 磁盘守护、磁盘阈值 preflight 与事故复盘归档。
- `next`: 先合并并验证磁盘守护与 deploy preflight，确保生产发布恢复可信；随后回到既定顺序，继续 `Lane F`、再进入 `Lane H` 与 `Lane I`

## 5. Blockers

- `blocker`: 无
  - `impact`: 无
  - `owner`: 无

## 6. Validation

- `latest_validation`: `2026-04-09` `Lane G` 完成后已通过 `apps/api` typecheck 与 `git diff --check`；随后 production 事故已完成数据库现场核验、MinIO 对象确认与主机磁盘清理验证；本轮 ops hardening 将补充 shell 语法校验、`pnpm validate` 与文档一致性检查
- `result`: `passed_with_existing_web_issue_and_ops_followup`
- `latest_failure`: `apps/web/src/features/search/pages/search-page.tsx(215,52):` `SearchFilters` 缺少 `index_status`；另有 production deploy 曾因远端磁盘写满导致 `${DEPLOY_PATH}/shared/tmp` 无法创建，已作为 ops hardening 修复项收口

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
- `generated_contract`: [Phase 2A OpenAPI](/Users/coderlauu/xRag/docs/generated/openapi/phase-2a-api.json)
- `ops_runbook`: [deploy README](/Users/coderlauu/xRag/deploy/README.md), [production inspection guide](/Users/coderlauu/xRag/deploy/production-inspection-guide.md)
- `incident_retro`: [2026-04-09 production data loss and deploy incident retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-09-production-data-loss-and-deploy-incident-retrospective.md)
- `active_ops_plan`: [Production Disk Guard And Incident Retro](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-09-production-disk-guard-and-incident-retro.md)
- `key_commits`: `01591c0`, `12e26bf`, `ca995e6`, `dd22d54`, `452d68e`, `bdd2073`, `158c258`, `ca138aa`
- `latest_ci_run`: `24143542091`（失败根因已确认为远端磁盘写满；清理后待重跑最新 main）
