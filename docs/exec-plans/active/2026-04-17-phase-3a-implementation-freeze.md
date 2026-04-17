# Phase 3A Implementation Freeze

## 1. Metadata

- `plan_id`: `phase-3a-implementation-freeze`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [v7 handoff](/Users/coderlauu/xRag/docs/handoff/v7.md), [v7 status](/Users/coderlauu/xRag/docs/status/v7-phase-3a.md), [Phase 3A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md), [Phase 3A api design](/Users/coderlauu/xRag/tech/api/2026-04-17-phase-3a-api.md), [Phase 3A data model](/Users/coderlauu/xRag/tech/data-model/2026-04-17-phase-3a-data-model.md), [Phase 3A architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-architecture.md), [Phase 3A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-prd.md), [Phase 3A backlog](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-backlog.md)

## 2. Objective

把已冻结的 `Phase 3A` contract 转成可以进入 implementation lanes 的落地计划：明确写入边界、lane 顺序、测试层级、OpenAPI/SDK 生成点和集成风险。

## 3. Scope

### In Scope

- 拆分 `Contract To Code / API read models / Web ops diagnostics / Testing + release readiness` 实现 lane
- 明确主线程必须先完成的 contract-to-code 写入范围
- 明确哪些 lane 可以在 contract-to-code 完成后并行
- 明确最小测试矩阵：
  - API integration
  - shared-types/API client contract
  - Web build/typecheck
  - E2E/smoke 如涉及页面可达性
- 明确 rollback 与兼容策略

### Out Of Scope

- 在 implementation freeze 阶段直接改代码
- 未完成写入边界前下放子 agent
- 修改已冻结路径、字段名、状态枚举和主数据模型语义
- 自动 remediation、自动 rollback、团队协作或日志平台

## 4. Assumptions

- `Phase 3A Contract Freeze` 已完成，路径和 DTO 方向不再临时更改
- 当前实现继续以 `v6 / Phase 2C` 绿态为运行基线
- 当前 `P0` 默认不新增 durable table

## 5. Risks

- 若 contract-to-code 和 API aggregation 混在一个 lane，容易同时打破 OpenAPI、SDK 和 Web
- 若 Web lane 先于 API contract 实现，会重新回到前端拼装临时对象
- 若 integration tests 不覆盖 compare/replay 空数据场景，production 首批数据不完整时会失稳

## 6. Plan

1. 将 `Phase 3A Contract Freeze` 映射到具体文件写入范围。
2. 拆分 implementation lanes，并定义每个 lane 的 owner、输入、输出、验证与禁止事项。
3. 更新 `handoff / status / current / AGENTS`，确认进入 implementation lanes 的 gate。
4. 通过文档校验后，再进入实际代码实现。

## 7. Validation

- 当前阶段为文档与计划变更，至少跑 `pnpm docs:check` 与 `git diff --check`
- 后续实现阶段按 lane 补充 `pnpm validate / test:unit / test:integration / web build / e2e smoke`

## 8. Rollback

- 若写入边界无法拆清，回退到 `contract-freeze`，不得直接并行实现

## 9. Decision Log

- `2026-04-17`: `Phase 3A Contract Freeze` 已完成，当前进入 `implementation-freeze`
