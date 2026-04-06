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
- `upload-storage`: `in-progress`
- `worker-pipeline`: `in-progress`
- `web-integration`: `not-started`
- `testing`: `in-progress`
- `ci-cd`: `completed`

## 4. Current Node

- `now`: `Phase 1B harness hardening` 的 `P1/P2` 最小闭环已落库，已补 `retry regression / structure check / docs check / ops inspect script / deploy evidence archive`
- `next`: 在 Docker 可用环境补跑 `integration / smoke` 后，进入 `multipart web flow / pdf parse / frontend diagnostics / ops board` 并行实现

## 5. Blockers

- `blocker`: 当前 production 基线存在文件上传接口错误，疑似发生在 `uploads/initiate` 或 `uploads/complete` 调用 `MinIO / S3-compatible storage` 的环节
  - `impact`: 当前文件导入主链路不可稳定验证，会影响 `upload-storage` 与后续 `pdf / multipart` 增量设计
  - `owner`: `codex`
- `secondary_blocker`: 当前 shell 环境未连接 Docker daemon，尚未完成本轮新增 migration 与上传 contract 的 integration 验证
  - `impact`: `P0` 代码与生成链已落库，但 `upload -> complete -> queue` 的本地集成证据仍待补齐
  - `owner`: `codex`

## 6. Validation

- `latest_validation`: `2026-04-06` 已通过 `pnpm validate`，包含 `lint + typecheck + contract:check + structure:check + docs:check`
- `result`: `passed`
- `latest_failure`: 本地 `API integration` 因当前 shell 缺少 Docker daemon 未执行；`CI` 失败闭环仍待线上自动归档验证

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v2.md](/Users/coderlauu/xRag/docs/handoff/v2.md)
- `prototype`: [prototype/v2](/Users/coderlauu/xRag/prototype/v2/index.html)
- `interaction_spec`: [v2 interaction spec](/Users/coderlauu/xRag/design/spec/2026-04-06-v2-interaction-spec.md)
- `tech_docs`: [Phase 1B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-06-phase-1b-architecture.md), [Phase 1B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-06-phase-1b-data-model.md), [Phase 1B api design](/Users/coderlauu/xRag/tech/api/2026-04-06-phase-1b-api.md), [Phase 1A architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md), [Phase 1A data model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md), [Phase 1A api design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)
- `exec_plans`: [Phase 1B planning and design](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-02-phase-1b-planning-and-design.md), [CI failure loop](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-04-ci-failure-loop.md), [Phase 1B prototype and interaction](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-06-phase-1b-prototype-and-interaction.md), [Phase 1B harness hardening](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-06-phase-1b-harness-hardening.md)
- `ops_runbook`: [production inspection guide](/Users/coderlauu/xRag/deploy/production-inspection-guide.md)
- `key_commits`: `431e4cc`, `62401b9`, `b997aea`
- `latest_ci_run`: `23969230230`
