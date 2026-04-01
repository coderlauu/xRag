# v1 / Phase 1A Status

## 1. Metadata

- `version`: `v1`
- `phase`: `Phase 1A`
- `status`: `in-progress`
- `owner`: `codex`
- `updated_at`: `2026-04-01`

## 2. Goal

### In Scope

- 打通 `Inbox / Search / Detail` 三页真实闭环
- 支持手动文本保存、文件上传、解析状态展示、关键词搜索、基础筛选、详情查看
- 建立 `web + api + worker + db + storage + queue` 的正式工程基线

### Out Of Scope

- AI 问答
- 语义搜索
- 真实 OCR
- 浏览器插件
- 团队协作
- 移动端

## 3. Milestones

- `foundation`: `completed`
- `api-persistence`: `in-progress`
- `upload-storage`: `not-started`
- `worker-pipeline`: `not-started`
- `web-integration`: `in-progress`
- `testing`: `not-started`
- `ci-cd`: `in-progress`

## 4. Current Node

- `now`: 收口 `apps/api` 的持久化链路，把数据库、OpenAPI、repository/service/controller 分层整理成可提交状态
- `next`: 打通 `uploads -> object storage -> queue -> worker -> web` 的真实状态流

## 5. Blockers

- `blocker`: `worker`、对象存储和前端联调仍依赖 API 事实源完全稳定
  - `impact`: 上游 contract 和状态机未冻结前，不适合大规模并行实现
  - `owner`: `codex`

## 6. Validation

- `latest_validation`: GitHub Actions `CI` run `23803897042`
- `result`: `passed`
- `latest_failure`: `23803220970` 的 `infra` job 因为 Postgres readiness 未等待完成而失败，已通过 CI 编排与重试修复

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v1.md](/Users/coderlauu/xRag/docs/handoff/v1.md)
- `tech_docs`: [architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md), [data model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md), [api](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)
- `exec_plans`: [engineering bootstrap](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-03-31-phase-1a-engineering-bootstrap.md), [persistence and api layer](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-03-31-phase-1a-persistence-and-api-layer.md)
- `key_commits`: `fe4dc4c`, `1ff99dc`, `d80588e`, `a2d42ae`, `dcb3098`
- `latest_ci_run`: `23803897042`
