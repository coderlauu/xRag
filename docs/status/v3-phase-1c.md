# v3 / Phase 1C Status

## 1. Metadata

- `version`: `v3`
- `phase`: `Phase 1C`
- `status`: `in-progress`
- `owner`: `codex`
- `updated_at`: `2026-04-07`

## 2. Goal

### In Scope

- 在已完成 PDF 与上传稳态的基础上，增强扫描件与链接内容接入能力，并提升检索质量与运营可观测性。
- 扫描版 PDF OCR
- 链接正文抓取
- 搜索排序与匹配解释增强
- 文档处理时间线与诊断展示增强

### Out Of Scope

- AI 问答
- 向量检索 / 语义召回
- 浏览器插件
- 团队协作 / 权限模型
- 移动端
- 全自动生产修复与自动发版

## 3. Milestones

- `foundation`: `completed`
- `api-persistence`: `completed`
- `upload-storage`: `completed`
- `worker-pipeline`: `completed`
- `web-integration`: `completed`
- `testing`: `completed`
- `ci-cd`: `not-started`

## 4. Current Node

- `now`: 已完成 `Lane C 检索解释 / 时间线展示`，搜索页会展示命中说明、排序提示和命中字段，详情页接入真实处理时间线并完成 E2E 回归
- `next`: 进入 `Lane D 运维可见性 / observability`，把 OCR、抓取、投影和回滚基线进一步收口到运维看板

## 5. Blockers

- `blocker`: 无
  - `impact`: 无
  - `owner`: 无

## 6. Validation

- `latest_validation`: 已通过 `@xrag/api typecheck`、`@xrag/web typecheck`、`@xrag/web build`、`./scripts/run-e2e-smoke.sh` 以及 `PATH="/Users/coderlauu/xRag/scripts/bin:$PATH" pnpm validate`；其中新增覆盖 `搜索解释展示 -> 详情时间线 -> E2E 路径回归`
- `result`: `passed`
- `latest_failure`: 无

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v3.md](/Users/coderlauu/xRag/docs/handoff/v3.md)
- `prototype`: [prototype/v3](/Users/coderlauu/xRag/prototype/v3/index.html)
- `interaction_spec`: [v3 interaction spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v3-interaction-spec.md)
- `tech_docs`: [Phase 1C architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-1c-architecture.md), [Phase 1C data model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-1c-data-model.md), [Phase 1C api design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-1c-api.md), [Phase 1B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-06-phase-1b-architecture.md), [Phase 1B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-06-phase-1b-data-model.md), [Phase 1B api design](/Users/coderlauu/xRag/tech/api/2026-04-06-phase-1b-api.md)
- `exec_plans`: [Phase 1C Planning and Design](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-07-phase-1c-planning-and-design.md), [Phase 1C Implementation Lanes](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-07-phase-1c-implementation-lanes.md)
- `generated_contract`: [Phase 1C OpenAPI](/Users/coderlauu/xRag/docs/generated/openapi/phase-1c-api.json)
- `key_commits`: `2e14f94`, `2df032a`, `479a152`, `ba10dd5`
- `latest_ci_run`: `待下一个 Lane C 提交触发`
