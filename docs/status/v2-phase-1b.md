# v2 / Phase 1B Status

## 1. Metadata

- `version`: `v2`
- `phase`: `Phase 1B`
- `status`: `in-progress`
- `owner`: `codex`
- `updated_at`: `2026-04-06`

## 2. Goal

### In Scope

- 支持 `pdf` 真实解析
- 支持 multipart 大文件上传
- 增强上传与解析失败诊断
- 建立 production 观测与回滚基线

### Out Of Scope

- AI 问答
- 语义搜索
- OCR
- 浏览器插件
- 团队协作
- 移动端

## 3. Milestones

- `foundation`: `completed`
- `api-persistence`: `completed`
- `upload-storage`: `completed`
- `worker-pipeline`: `completed`
- `web-integration`: `completed`
- `testing`: `in-progress`
- `ci-cd`: `completed`
- `contract-freeze`: `completed`

## 4. Current Node

- `now`: 已完成 `Lane A/B/C/D` 的核心闭环补强，包括 multipart 完成校验、PDF 解析成功/失败单测、Search/Detail 的诊断可视化，以及 Ops Board 的真实读接口与运营态
- `next`: 继续补一条更接近真实上传链路的 PDF 回归证据，并评估 `Phase 1B` 是否可以进入收口验收

## 5. Blockers

- `blocker`: 当前仍缺一条更接近真实用户路径的 PDF 上传回归证据，用于覆盖“上传 PDF -> worker 解析 -> 搜索/详情呈现 -> 诊断可见”的整链路
  - `impact`: `Phase 1B` 的核心能力已可用，但在收口前还缺一条更强的回归样本
  - `owner`: `codex`

## 6. Validation

- `latest_validation`: `2026-04-06` 已通过 `pnpm validate`、`pnpm --filter @xrag/shared-types typecheck`、`pnpm --filter @xrag/api typecheck`、`pnpm --filter @xrag/api openapi:generate`、`./scripts/run-api-integration.sh`、`pnpm --filter @xrag/worker test:unit`、`pnpm --filter @xrag/worker build`、`pnpm --filter @xrag/web typecheck`、`pnpm --filter @xrag/web build`，并通过 GitHub Actions run `24034875537` 的 `validate / integration / e2e / infra / build-images / deploy-production / smoke-production`
- `result`: `passed`
- `latest_failure`: `CI` 失败闭环已验证可自动归档；当前残留仅是更贴近真实用户路径的 PDF 整链路回归样本尚未补齐

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v2.md](/Users/coderlauu/xRag/docs/handoff/v2.md)
- `prototype`: [prototype/v2](/Users/coderlauu/xRag/prototype/v2/index.html)
- `interaction_spec`: [v2 interaction spec](/Users/coderlauu/xRag/design/spec/2026-04-06-v2-interaction-spec.md)
- `tech_docs`: [Phase 1B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-06-phase-1b-architecture.md), [Phase 1B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-06-phase-1b-data-model.md), [Phase 1B api design](/Users/coderlauu/xRag/tech/api/2026-04-06-phase-1b-api.md), [Phase 1A architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md), [Phase 1A data model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md), [Phase 1A api design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)
- `generated_contract`: [Phase 1B OpenAPI](/Users/coderlauu/xRag/docs/generated/openapi/phase-1b-api.json)
- `exec_plans`: [Phase 1B planning and design](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-02-phase-1b-planning-and-design.md), [Phase 1B prototype and interaction](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-06-phase-1b-prototype-and-interaction.md), [CI failure loop](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-04-ci-failure-loop.md), [Phase 1B harness hardening](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-06-phase-1b-harness-hardening.md), [Phase 1B implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-06-phase-1b-implementation-lanes.md)
- `ops_runbook`: [production inspection guide](/Users/coderlauu/xRag/deploy/production-inspection-guide.md)
- `key_commits`: `62401b9`, `b997aea`, `c20835e`, `e3832ef`
- `latest_ci_run`: `24034875537`
