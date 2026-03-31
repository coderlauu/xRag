# Exec Plan Template

## 1. Metadata

- `plan_id`: `phase-1a-engineering-bootstrap`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [Phase 1A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md), [Phase 1A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md), [Phase 1A API Design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)

## 2. Objective

搭建 xRag Phase 1A 的正式工程骨架，并为后续并行开发建立稳定边界。

## 3. Scope

### In Scope

- 初始化 monorepo 与基础脚本
- 创建 `apps/web`、`apps/api`、`apps/worker`、`packages/*`
- 补齐本地 compose 与 CI 骨架
- 定义第一轮并行开发 ownership

### Out Of Scope

- 完整业务功能实现
- 数据库 schema 与 migration 细节落地
- 搜索与上传链路的完整实现

## 4. Assumptions

- Phase 1A 仍以单用户场景为主
- 本轮先把工程约束和运行骨架建立起来，再分模块并行填充

## 5. Risks

- 过早并行会导致 contract 漂移
- 目录结构未稳定时子 agent 容易互相覆盖

## 6. Plan

1. 主线程完成骨架与约束
2. 创建 worktree 并分配 `web / worker / ci-infra`
3. 并行开发后回主线程整合

## 7. Validation

- 单元测试：当前仅验证 TypeScript 编译与基础脚本可运行
- 集成测试：后续在 API/worker 数据链路落地后补齐
- E2E / smoke：后续在三页闭环连通后补齐

## 8. Rollback

- 如骨架选型有误，可回退到最近文档化提交并重建工程目录

## 9. Decision Log

- `2026-03-31`: 先搭正式工程骨架，再开 worktree 并行
- `2026-03-31`: monorepo/web/api/worker/ci 骨架已完成，计划移入 completed
