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
- `upload-storage`: `in-progress`
- `worker-pipeline`: `completed`
- `web-integration`: `not-started`
- `testing`: `in-progress`
- `ci-cd`: `not-started`

## 4. Current Node

- `now`: 已完成 `Lane A OCR` 与 `Lane B Link Ingest` 的后端主链路，worker 能在 PDF 文本层为空时转入 OCR，并完成链接抓取、OCR 投影与时间线事件写入
- `next`: 进入 `Lane C 检索解释 / 时间线展示`，把前端搜索页、详情页与 E2E 覆盖收口到 `Phase 1C` 原型要求

## 5. Blockers

- `blocker`: 无
  - `impact`: 无
  - `owner`: 无

## 6. Validation

- `latest_validation`: 已通过 `@xrag/worker test:unit`、`./scripts/run-api-integration.sh` 与 `pnpm validate`；其中新增链路覆盖 `documents/link -> fetch_link worker -> detail/timeline` 以及 `scanned pdf -> run_ocr -> detail/timeline`
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
- `key_commits`: `2e14f94`, `2df032a`, `2ed1ecb`, `479a152`
- `latest_ci_run`: `24067191296`
