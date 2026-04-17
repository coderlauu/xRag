# Phase 3A Contract Freeze Exec Plan

## 1. Metadata

- `plan_id`: `phase-3a-contract-freeze`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [v7 handoff](/Users/coderlauu/xRag/docs/handoff/v7.md), [v7 status](/Users/coderlauu/xRag/docs/status/v7-phase-3a.md), [Phase 3A architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-architecture.md), [Phase 3A data model](/Users/coderlauu/xRag/tech/data-model/2026-04-17-phase-3a-data-model.md), [Phase 3A api design](/Users/coderlauu/xRag/tech/api/2026-04-17-phase-3a-api.md), [Phase 3A contract freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-17-phase-3a-contract-freeze-prerequisites.md), [Phase 3A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md)

## 2. Objective

在 `Phase 3A` 技术评估已完成后，正式冻结 `diagnostic_sample / answer replay / document replay / deployment compare` 的 read model、资源路径、query、shared types 与 schema 边界，并为后续 `implementation-freeze` 做准备。

## 3. Scope

### In Scope

- 冻结 `GET /api/v1/ops/samples`
- 冻结 `GET /api/v1/ops/replays/answer-sessions/:sessionId`
- 冻结 `GET /api/v1/ops/replays/documents/:documentId`
- 冻结 `GET /api/v1/ops/deployments/compare`
- 冻结 `OpsDiagnosticSample`、replay wrapper、deployment compare DTO 方向
- 冻结 `sample_kind / origin / regression_class / replay freshness flags` 的应用层枚举
- 冻结 `Phase 3A / P0` 默认不新增 durable table、不改写既有状态机
- 更新 `handoff / status / current / AGENTS`

### Out Of Scope

- 直接实现 API、SDK、OpenAPI 或 Web 页面
- 直接进入 implementation lanes
- 新增持久表、执行编排、自动 remediation 或自动回滚
- 重写 `Ask / Search / Detail` 主状态机

## 4. Assumptions

- `Phase 3A` 技术评估已确认没有新的硬 blocker
- 当前代码基线已有 answer/document/ops 可复用事实面
- `Phase 2A / 2B / 2C` 的 `citation / refusal / freshness / release-readiness` 边界继续稳定
- 当前阶段仍是文档与 contract 冻结，不改运行时代码

## 5. Risks

- 若 `diagnostic_sample` 的主键不稳定，下游页面、测试和后续 compare 都会出现重复对象
- 若 replay wrapper 复制既有 DTO 字段，后续很容易出现同一事实两种定义
- 若 deployment compare 的“新回归”判定留给前端，会重复造成 heuristics 分叉
- 若在 freeze 阶段顺手加表，会把 `Phase 3A` 推向新的 observability 平台

## 6. Plan

1. 冻结 Phase 3A API resource paths、query、error 和 read model 语义。
2. 冻结 shared-types DTO、应用层枚举和复用既有 DTO 的边界。
3. 冻结 schema 边界与明确不新增的表、枚举、状态机语义。
4. 更新版本入口并创建 `implementation-freeze` active exec plan。

## 7. Validation

- 单元测试：不适用，当前为 contract 文档变更
- 集成测试：不适用，当前不改运行时代码
- E2E / smoke：不适用，当前不改运行时代码
- 文档阶段至少保证 `pnpm docs:check` 与 `git diff --check` 通过

## 8. Rollback

- 若 contract freeze 被证明存在硬缺口，回退到 `technical-evaluation`，不得在未更新 freeze 文档的情况下进入 implementation freeze 或实现 lane

## 9. Decision Log

- `2026-04-17`: `Phase 3A` 技术评估确认 `read model 增量、零新表、ops 资源族` 方向成立
- `2026-04-17`: 已形成 [Phase 3A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md)
- `2026-04-17`: active exec plan 从 `technical-evaluation` 切换到 `implementation-freeze`
