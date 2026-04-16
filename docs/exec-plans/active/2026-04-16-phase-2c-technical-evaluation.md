# Exec Plan Template

## 1. Metadata

- `plan_id`: `phase-2c-technical-evaluation`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: `docs/handoff/v6.md`, `docs/status/v6-phase-2c.md`, `docs/prd/2026-04-16-xrag-phase-2c-prd.md`, `docs/prd/2026-04-16-xrag-phase-2c-backlog.md`, `design/spec/2026-04-16-v6-interaction-delta.md`, `docs/decisions/2026-04-16-phase-2c-p0-technical-tradeoffs.md`

## 2. Objective

在 `v6 / Phase 2C` 已完成产品范围冻结后，对 `corpus readiness / quality scorecard / incident clustering / release guard` 进行正式技术评估，明确事实源、架构边界、数据模型方向和 API 扩展策略，并判断是否进入 `contract-freeze`。

## 3. Scope

### In Scope

- 审核现有 `ops / answer summary / health / incidents / deployment` 事实源与 `Phase 2A Evaluation Plan`
- 评估 `Phase 2C P0` 的架构、数据模型、API 和 shared contract 边界
- 识别哪些指标可沿用现有资源，哪些需要预聚合或新事实源
- 形成进入 `contract-freeze` 前的 prerequisites
- 更新 `handoff / status / current / AGENTS`，保证下一步可恢复

### Out Of Scope

- 直接修改 schema、API 或生产代码
- 直接进入 implementation lanes
- 自动 remediation、自动回滚、自动重建索引
- 重新打开 `Ask / Search / Detail` 主链的用户体验主线

## 4. Assumptions

- `v6 / Phase 2C` 已完成第一版 `PRD / backlog / interaction delta / tradeoff`
- GitHub Actions run `24485981323` 已成功，当前 main 维持绿态
- `Phase 2A / 2B` 已冻结 `citation / refusal / freshness / release-readiness` 主边界
- 当前 `ops` 页面和 `/ops/*` 资源已经存在最小事实源，但尚未形成完整治理主板

## 5. Risks

- 若直接把现有接口堆到一个页面上，可能形成“数字很多但没有治理判断”的伪面板
- 若离线评估口径与在线趋势口径分叉，`ops` 板会失去可信度
- 若过早承诺自动 remediation，会把版本边界拉向执行编排
- 若过度预聚合，可能导致页面展示与真实运行事实脱节

## 6. Plan

1. 审核现有 `ops / eval / readiness / deployment` 事实源，整理 `Phase 2C P0` 的真实依赖。
2. 形成 `architecture / data-model / api / contract-freeze-prerequisites` 第一版技术评估文档。
3. 根据边界稳定度决定是否切换到 `contract-freeze`，并同步更新 `status / current / AGENTS`。

## 7. Validation

- 单元测试：本阶段不涉及代码实现
- 集成测试：本阶段不涉及代码实现
- E2E / smoke：本阶段不涉及代码实现；文档阶段至少保证 `pnpm docs:check` 通过

## 8. Rollback

- 若 `Phase 2C P0` 在技术评估阶段被证明范围不成立，回退到 `planning-and-scope`，或重新缩减为更小的治理切片

## 9. Decision Log

- `2026-04-16`: `v6 / Phase 2C` 已完成第一版 `PRD / backlog / interaction delta / technical tradeoff`
- `2026-04-16`: 产品范围已冻结为 `corpus readiness / quality scorecard / incident clustering / release guard`
- `2026-04-16`: active exec plan 从 `planning-and-scope` 切换到 `technical-evaluation`
