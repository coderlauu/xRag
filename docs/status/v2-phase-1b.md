# v2 / Phase 1B Status

## 1. Metadata

- `version`: `v2`
- `phase`: `Phase 1B`
- `status`: `in-progress`
- `owner`: `codex`
- `updated_at`: `2026-04-02`

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

- `now`: 除了 `Phase 1B` 规划外，已记录一个当前 production 基线 bug：点击 `Upload File` 后接口报错，表现为 `MinIO / storage` 相关错误，归类到当前版本的 `upload-storage` lane
- `next`: 先排查并修复上传链路中的 `MinIO` 相关错误，再继续冻结 `pdf / multipart / observability` 的新增 contract

## 5. Blockers

- `blocker`: 当前 production 基线存在文件上传接口错误，疑似发生在 `uploads/initiate` 或 `uploads/complete` 调用 `MinIO / S3-compatible storage` 的环节
  - `impact`: 当前文件导入主链路不可稳定验证，会影响 `upload-storage` 与后续 `pdf / multipart` 增量设计
  - `owner`: `codex`
- `secondary_blocker`: `Phase 1B` 的数据模型与 API 增量尚未冻结
  - `impact`: 在 contract 冻结前，不适合直接并行实现新增能力
  - `owner`: `codex`

## 6. Validation

- `latest_validation`: `Phase 1A` production 已通过，最近成功 run 为 `23856744666`
- `result`: `passed`
- `latest_failure`: 下一阶段尚未进入实现，暂无 `Phase 1B` 独立失败记录

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v2.md](/Users/coderlauu/xRag/docs/handoff/v2.md)
- `tech_docs`: [Phase 1A architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md), [Phase 1A data model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md), [Phase 1A api design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)
- `exec_plans`: [Phase 1B planning and design](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-02-phase-1b-planning-and-design.md)
- `ops_runbook`: [production inspection guide](/Users/coderlauu/xRag/deploy/production-inspection-guide.md)
- `key_commits`: `d15399b`, `cb8efc1`, `92f55a7`
- `latest_ci_run`: `23856744666`
