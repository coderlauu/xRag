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
- `testing`: `not-started`
- `ci-cd`: `completed`

## 4. Current Node

- `now`: `CI Failure Loop` 第一版已落库，当前正在补齐 `Phase 1B` 的 `prototype/v2` 与 interaction spec，为 `pdf / multipart / observability` 的增量 contract 提供产品事实源
- `next`: 以 `prototype/v2` 和 `v2 interaction spec` 为基础，冻结 `Phase 1B` 的页面状态、数据对象和 API 增量边界

## 5. Blockers

- `blocker`: 当前 production 基线存在文件上传接口错误，疑似发生在 `uploads/initiate` 或 `uploads/complete` 调用 `MinIO / S3-compatible storage` 的环节
  - `impact`: 当前文件导入主链路不可稳定验证，会影响 `upload-storage` 与后续 `pdf / multipart` 增量设计
  - `owner`: `codex`
- `secondary_blocker`: `Phase 1B` 的数据模型与 API 增量尚未冻结
  - `impact`: 在 contract 冻结前，不适合直接并行实现新增能力
  - `owner`: `codex`

## 6. Validation

- `latest_validation`: `Phase 1A` production 已通过，最近成功 run 为 `23969230230`
- `result`: `passed`
- `latest_failure`: `CI` 失败闭环尚未进入线上自动归档验证，本次实现后将以独立 workflow 记录失败 run 的 incident artifact

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v2.md](/Users/coderlauu/xRag/docs/handoff/v2.md)
- `prototype`: [prototype/v2](/Users/coderlauu/xRag/prototype/v2/index.html)
- `interaction_spec`: [v2 interaction spec](/Users/coderlauu/xRag/design/spec/2026-04-06-v2-interaction-spec.md)
- `tech_docs`: [Phase 1A architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md), [Phase 1A data model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md), [Phase 1A api design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)
- `exec_plans`: [Phase 1B planning and design](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-02-phase-1b-planning-and-design.md), [CI failure loop](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-04-ci-failure-loop.md), [Phase 1B prototype and interaction](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-06-phase-1b-prototype-and-interaction.md)
- `ops_runbook`: [production inspection guide](/Users/coderlauu/xRag/deploy/production-inspection-guide.md)
- `key_commits`: `431e4cc`, `62401b9`, `b997aea`
- `latest_ci_run`: `23969230230`
