# Phase 2C Implementation Freeze Exec Plan

## 1. Metadata

- `plan_id`: `phase-2c-implementation-freeze`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: `docs/handoff/v6.md`, `docs/status/v6-phase-2c.md`, `tech/architecture/2026-04-16-phase-2c-contract-freeze.md`

## 2. Objective

在 `Phase 2C` contract 已冻结后，把实现顺序、文件 ownership、验证边界和可并行 lane 切分固定下来，为后续代码实现做准备。

## 3. Scope

### In Scope

- 固定主线程 `Lane 0` 的 contract-to-code 范围：
  - schema / migration
  - shared-types
  - DTO / OpenAPI
  - api-client
  - web api adapter
- 切分后续 implementation lanes：
  - API read model
  - deploy/eval ingestion
  - web ops board
  - tests / e2e / smoke
- 固定验证命令和 release-readiness gate
- 更新 `handoff / status / current / AGENTS`

### Out Of Scope

- 直接进入代码实现
- 修改已经冻结的 contract
- 自动 remediation、自动回滚、自动重建索引
- 新建 `/api/v1/evals/*` 资源族

## 4. Assumptions

- [Phase 2C Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-contract-freeze.md) 已成为最新 contract source-of-truth
- GitHub Actions run `24486856197` 已成功，当前 main 维持绿态
- 后续实现必须先由主线程完成 schema/shared-types/API contract，再拆并行 lane

## 5. Risks

- 如果并行 lane 在 `Lane 0` 之前启动，API / shared-types 容易漂移
- 如果把 `evaluation_runs` ingestion 和 `ops overview` read model 混在一个 lane，容易难以验证
- 如果 web 先实现 mock 面板，后端 contract 可能被 UI 反向牵引

## 6. Plan

1. 审核 contract freeze 与现有代码结构，列出必须由主线程先落地的文件与验证。
2. 切分 `Lane 0 / A / B / C / D` 的实现 ownership 和 DoD。
3. 若边界稳定，切换到 `implementation-lanes` 并开始编码。

## 7. Validation

- 单元测试：本阶段不涉及代码实现
- 集成测试：本阶段不涉及代码实现
- E2E / smoke：本阶段不涉及代码实现；文档阶段至少保证 `pnpm docs:check` 通过

## 8. Rollback

- 若 implementation freeze 发现 contract 存在缺口，回退到 `contract-freeze`，不得直接编码

## 9. Decision Log

- `2026-04-16`: `Phase 2C` contract freeze 已完成
- `2026-04-16`: active exec plan 从 `contract-freeze` 切换到 `implementation-freeze`
