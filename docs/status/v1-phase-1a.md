# v1 / Phase 1A Status

## 1. Metadata

- `version`: `v1`
- `phase`: `Phase 1A`
- `status`: `completed`
- `owner`: `codex`
- `updated_at`: `2026-04-02`

## 2. Goal

### In Scope

- 打通 `Inbox / Search / Detail` 三页真实闭环
- 支持手动文本保存、文件上传、解析状态展示、关键词搜索、基础筛选、详情查看
- 建立 `web + api + worker + db + storage + queue` 的正式工程基线

### Out Of Scope

- AI 问答
- 语义搜索
- 真实 OCR
- `pdf` 真实解析
- multipart 大文件上传
- 浏览器插件
- 团队协作
- 移动端

## 3. Milestones

- `foundation`: `completed`
- `api-persistence`: `completed`
- `upload-storage`: `completed`
- `worker-pipeline`: `completed`
- `web-integration`: `completed`
- `testing`: `completed`
- `ci-cd`: `completed`

## 4. Current Node

- `now`: `Phase 1A` 主链路、测试基线与 production 部署已全部完成，版本已进入收口归档
- `next`: 当前入口已切换到 `v2 / Phase 1B`，后续进入 `pdf` 真实解析、multipart 上传与 production hardening 的新阶段

## 5. Blockers

- `blocker`: `none`
  - `impact`: `Phase 1A` 无剩余 blocker
  - `owner`: `codex`

## 6. Validation

- `latest_validation`: `2026-04-01` GitHub Actions `23856744666` 已完成 `validate / integration / e2e / build-images / deploy-production / smoke-production`，production 可访问
- `result`: `passed`
- `latest_failure`: 历史失败集中在部署链路：SSH 认证、Docker 权限、镜像拉取速度、`api-migrate` 吃掉 SSH stdin、`Caddyfile` 挂载路径。以上问题已全部在 `Phase 1A` 收口过程中修复

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v1.md](/Users/coderlauu/xRag/docs/handoff/v1.md)
- `tech_docs`: [architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md), [data model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md), [api](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)
- `exec_plans`: [engineering bootstrap](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-03-31-phase-1a-engineering-bootstrap.md), [persistence and api layer](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-03-31-phase-1a-persistence-and-api-layer.md), [parallel implementation](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-01-phase-1a-parallel-implementation.md)
- `key_commits`: `fe4dc4c`, `690bb1d`, `d15399b`, `cb8efc1`, `3268245`, `92f55a7`
- `latest_ci_run`: `23856744666`
