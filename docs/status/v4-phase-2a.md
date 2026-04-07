# v4 / Phase 2A Status

## 1. Metadata

- `version`: `v4`
- `phase`: `Phase 2A`
- `status`: `in-progress`
- `owner`: `codex`
- `updated_at`: `2026-04-07`

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

- `now`: 已完成 `PRD / handoff / status / planning exec plan / interaction spec / prototype / evaluation plan` 的 planning 资产冻结，`Grounded AI Retrieval & Answering` 的主线、scope、citation、freshness 与 eval 目标已形成统一产品基线
- `next`: 若继续推进，下一步应先决定是否进入 `Phase 2A` 技术方案设计节点，再判断是否需要新建 `tech/*` 文档与实现拆 lane

## 5. Blockers

- `blocker`: 无
  - `impact`: 无
  - `owner`: 无

## 6. Validation

- `latest_validation`: `2026-04-07` 已完成 `PRD / handoff / status / exec plan / interaction spec / prototype / evaluation plan` 的交叉核对，并通过 `git diff --check`；当前仍未进入技术方案实现与 CI 验证，验证口径以文档一致性、原型可读性与版本治理完整性为准
- `result`: `passed`
- `latest_failure`: 无

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v4.md](/Users/coderlauu/xRag/docs/handoff/v4.md)
- `prd`: [Phase 2A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-prd.md)
- `prototype`: [prototype/v4](/Users/coderlauu/xRag/prototype/v4/index.html)
- `interaction_spec`: [v4 interaction spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v4-interaction-spec.md)
- `tech_docs`: 当前 `Phase 2A` 尚未创建正式 `tech/*` 文档；实现基线继续参考 [Phase 1C architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-1c-architecture.md), [Phase 1C data model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-1c-data-model.md), [Phase 1C api design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-1c-api.md)
- `exec_plans`: [Phase 2A planning and design](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-07-phase-2a-planning-and-design.md), [Phase 1C planning and design](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-07-phase-1c-planning-and-design.md), [Phase 1C implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-07-phase-1c-implementation-lanes.md)
- `evaluation_plan`: [Phase 2A evaluation plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
- `generated_contract`: [Phase 1C OpenAPI](/Users/coderlauu/xRag/docs/generated/openapi/phase-1c-api.json)（`Phase 2A` 尚未进入 contract 生成节点）
- `ops_runbook`: [deploy README](/Users/coderlauu/xRag/deploy/README.md), [production inspection guide](/Users/coderlauu/xRag/deploy/production-inspection-guide.md)
- `key_commits`: `待补充`
- `latest_ci_run`: `沿用 v3 基线 24081424992`
