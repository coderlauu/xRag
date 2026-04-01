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
- `ci-cd`: `in-progress`

## 4. Current Node

- `now`: 主链路功能已完成，本轮新增了 `GHCR + SSH + docker compose` 的 `staging / production` CD 基线、部署脚本和 smoke 脚本
- `next`: 配置 GitHub environment secrets 并完成首次远端部署验证，然后整理 `Phase 1A` 收口提交；`pdf` 真实解析与 `multipart` 上传已转入下一阶段

## 5. Blockers

- `blocker`: `CD` 已实现代码基线，但还缺远端主机和 GitHub environment secrets 的真实接通验证
  - `impact`: 在 secrets 和远端环境配置完成前，`staging / production` 只能视为“已实现待验证”；`pdf` 真实解析与 `multipart` 上传不再计入 `Phase 1A` 完成条件
  - `owner`: `codex`

## 6. Validation

- `latest_validation`: `2026-04-01` 已通过本地 `./scripts/run-e2e-smoke.sh`、`./scripts/ci-validate.sh`、`bash -n deploy/scripts/remote-deploy.sh scripts/gh-deploy-ssh.sh scripts/smoke-http.sh`、`docker compose -f deploy/compose/stack.compose.yml --env-file deploy/env/staging.env.example config`
- `result`: `passed`
- `latest_failure`: 本地二次复跑 `./scripts/run-api-integration.sh` 时遇到宿主机 `6379` 端口被其他进程占用，不是本轮业务代码回归；上一轮 GitHub CI 锁文件失败已在 `c2aaa51` 修复

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v1.md](/Users/coderlauu/xRag/docs/handoff/v1.md)
- `tech_docs`: [architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md), [data model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md), [api](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)
- `exec_plans`: [engineering bootstrap](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-03-31-phase-1a-engineering-bootstrap.md), [persistence and api layer](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-03-31-phase-1a-persistence-and-api-layer.md), [parallel implementation](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-01-phase-1a-parallel-implementation.md)
- `key_commits`: `fe4dc4c`, `1ff99dc`, `d80588e`, `a2d42ae`, `dcb3098`, `c2aaa51`
- `latest_ci_run`: `23827391963`（最近一次已知 GitHub CI 通过）；当前工作区新增的 integration/e2e 变更已完成本地验证，待下一次 push 验证远端流水线
