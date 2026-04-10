# v4 / Phase 2A Status

## 1. Metadata

- `version`: `v4`
- `phase`: `Phase 2A`
- `status`: `completed`
- `owner`: `codex`
- `updated_at`: `2026-04-10`

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
- `api-persistence`: `completed`
- `upload-storage`: `completed`
- `worker-pipeline`: `completed`
- `web-integration`: `completed`
- `testing`: `completed`
- `ci-cd`: `completed`

## 4. Current Node

- `now`: `Phase 2A / P0` 已完成 `Lane 0`、`Lane A`、`Lane B`、`Lane C`、`Lane D`、`Lane E`、`Lane F`、`Lane G`、`Lane H` 与 `Lane I`，且已在本地 Docker/DB 环境完成 `integration + e2e/smoke` 实跑验证。latest main GitHub Actions run `24221150785` 已全绿，覆盖 `validate / infra / integration / e2e / build-images / deploy-production / smoke-production`；production smoke 已通过。此前 production smoke 失败的根因是 `remote-deploy.sh` 通过 SSH stdin 执行时，`docker compose exec` 读取并消耗了脚本后续内容，导致 app/caddy 未真正启动；已由 `db3b620` 在 compose exec 处重定向 stdin 修复。
- `next`: `Phase 2A` 可进入正式版本关闭 / 后续版本规划；若继续基于当前 main 推进，默认发布基线为 `db3b620` + GitHub Actions run `24221150785`

## 5. Blockers

- `blocker`: 无
  - `impact`: 无
  - `owner`: 无

## 6. Validation

- `latest_validation`: `2026-04-10` GitHub Actions run `24221150785` 在 commit `db3b62086e592d8e4d60e8e35be1f89fed66adb2` 上通过，覆盖 `validate`、`infra`、`integration`、`e2e`、`build-images`、`deploy-production` 与 `smoke-production`；其中 production deploy 用时 `2m6s`，production smoke 用时 `10s`。此前 `2026-04-09` 已在本地 Docker 环境完成 `./scripts/run-api-integration.sh`（`12/12` 通过）与 `./scripts/run-e2e-smoke.sh`（`4/4` 通过）
- `result`: `passed`
- `latest_failure`: `24200503254` 曾因 production deploy 脚本被 `docker compose exec` 消耗 stdin，导致 production smoke 连接 `xrag.coderlau.cn:443` 超时；已由 `db3b620` 修复并由 run `24221150785` 验证通过

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
- `exec_plans`: [Phase 2A implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-08-phase-2a-implementation-lanes.md), [Phase 2A implementation freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-08-phase-2a-implementation-freeze.md), [Phase 2A planning and design](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-07-phase-2a-planning-and-design.md), [Phase 1C planning and design](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-07-phase-1c-planning-and-design.md), [Phase 1C implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-07-phase-1c-implementation-lanes.md)
- `evaluation_plan`: [Phase 2A evaluation plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
- `generated_contract`: [Phase 2A OpenAPI](/Users/coderlauu/xRag/docs/generated/openapi/phase-2a-api.json)
- `ops_runbook`: [deploy README](/Users/coderlauu/xRag/deploy/README.md), [production inspection guide](/Users/coderlauu/xRag/deploy/production-inspection-guide.md)
- `incident_retro`: [2026-04-09 production data loss and deploy incident retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-09-production-data-loss-and-deploy-incident-retrospective.md)
- `completed_ops_plan`: [Production Disk Guard And Incident Retro](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-09-production-disk-guard-and-incident-retro.md)
- `key_commits`: `01591c0`, `12e26bf`, `ca995e6`, `dd22d54`, `452d68e`, `bdd2073`, `158c258`, `ca138aa`, `df3d9ed`, `4b0146e`, `f1a95c3`, `ecf2f60`, `db3b620`
- `latest_ci_run`: `24221150785`（success，production deploy 与 production smoke 已通过）
