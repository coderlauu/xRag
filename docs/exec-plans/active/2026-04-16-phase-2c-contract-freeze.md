# Exec Plan Template

## 1. Metadata

- `plan_id`: `phase-2c-contract-freeze`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: `docs/handoff/v6.md`, `docs/status/v6-phase-2c.md`, `tech/architecture/2026-04-16-phase-2c-architecture.md`, `tech/data-model/2026-04-16-phase-2c-data-model.md`, `tech/api/2026-04-16-phase-2c-api.md`, `docs/decisions/2026-04-16-phase-2c-contract-freeze-prerequisites.md`

## 2. Objective

在 `Phase 2C` 技术评估已完成后，正式冻结 `/ops/overview + /ops/trends`、`evaluation_runs + deployment_records`、相关 shared types、枚举、时间窗口与治理语义边界，为后续 implementation freeze 做准备。

## 3. Scope

### In Scope

- 冻结 `overview / trends` 的 API contract
- 冻结 `evaluation_runs / deployment_records` 的最小数据面
- 冻结 `runtime quality / evaluation quality / readiness / incident / release guard` 的 shared types
- 冻结最小枚举与分类：
  - `readiness_blocking_reason`
  - `recommended_action_code`
  - `release_guard_risk_level`
  - `trend_window`
  - `trend_source`
- 判断哪些轻量治理 notice 可以暴露到 `Ask / Search / Detail`
- 更新 `handoff / status / current / AGENTS`

### Out Of Scope

- 进入 implementation lanes
- 直接修改 schema、OpenAPI、SDK 或前端代码
- 自动 remediation、自动回滚、自动重建索引
- 新建 `/api/v1/evals/*` 资源族

## 4. Assumptions

- `Phase 2C` 第一版产品文档已完成
- 技术评估文档已完成，并确认没有新的硬 blocker
- GitHub Actions run `24486354750` 已成功，当前 main 维持绿态
- `Phase 2A / 2B` 的可信问答主边界仍保持不变

## 5. Risks

- 若在 freeze 中把 runtime 和 evaluation 指标再次混写，会直接破坏口径
- 若过度扩大 schema 或 API，会把 `Phase 2C` 拉向 observability 平台
- 若轻量治理 notice 进入终端页面时越界，可能污染主问答心智

## 6. Plan

1. 冻结 `overview / trends` 的 contract 与 shared types 分层。
2. 冻结 `evaluation_runs / deployment_records` 的数据对象和最小枚举。
3. 明确轻量 notice 边界，并同步更新状态入口，为 implementation freeze 做准备。

## 7. Validation

- 单元测试：本阶段不涉及代码实现
- 集成测试：本阶段不涉及代码实现
- E2E / smoke：本阶段不涉及代码实现；文档阶段至少保证 `pnpm docs:check` 通过

## 8. Rollback

- 若 contract freeze 发现 `Phase 2C` 仍缺少关键技术结论，回退到 `technical-evaluation`，不直接进入实现

## 9. Decision Log

- `2026-04-16`: `Phase 2C` 已完成第一版技术评估文档
- `2026-04-16`: `contract-freeze-prerequisites` 已确认无硬 blocker
- `2026-04-16`: active exec plan 从 `technical-evaluation` 切换到 `contract-freeze`
