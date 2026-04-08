# Phase 2A Implementation Freeze

## 1. Metadata

- `plan_id`: `phase-2a-implementation-freeze`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [v4 handoff](/Users/coderlauu/xRag/docs/handoff/v4.md), [v4 status](/Users/coderlauu/xRag/docs/status/v4-phase-2a.md), [Phase 2A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-prd.md), [Phase 2A backlog](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-backlog.md), [Phase 2A technical tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-p0-technical-tradeoffs.md), [Phase 2A implementation freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-implementation-freeze-prerequisites.md), [Phase 2A runtime contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md), [Phase 2A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md), [Phase 2A architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-2a-architecture.md), [Phase 2A data model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-2a-data-model.md), [Phase 2A api design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-2a-api.md)

## 2. Objective

在 `Phase 2A / P0` 已完成 planning、tradeoff 收敛与 runtime freeze prep 后，正式冻结 `schema / shared-types / API contract / 状态机 / citation / scope / eval contract`，为后续实现 lane 提供单一事实源。

## 3. Main Thread First

并行实现前，主线程必须先冻结：

1. `schema`
2. `shared-types`
3. `API contract`
4. `state machine`
5. `citation / scope / diagnosis / eval contract`

未完成这一步前，不启动 `worker / web / test` 并行 lane。

## 4. Deliverables

### Must Have

- [Phase 2A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md) 作为主线程冻结基线
- `Phase 2A data model / api / runtime contracts` 进入 `freeze-ready`
- 新 active exec plan 取代 planning exec plan，成为恢复开发默认入口

### Next Coding Entry

后续编码应按以下顺序进入：

1. `schema + migration`
2. `shared-types + DTO/OpenAPI`
3. `queue / worker orchestration`
4. `web + ops + eval wiring`

## 5. Out Of Scope

- 直接进入完整问答实现
- 在 contract 未冻结前提前生成 `Phase 2A` OpenAPI
- 并行实现 `P1` 和 `deferred`
- 提前做多模型路由、摘要、推荐、多轮对话

## 6. Validation

- `@xrag/api typecheck`
- `@xrag/worker typecheck`
- `docker compose config`
- `deploy stack compose config`
- `git diff --check`
- `corepack pnpm docs:check`

## 7. Risks

- 若 schema 与 shared-types 不同步冻结，后续 API 与前端会出现双重返工
- 若先写 handler 再补 citation/scope contract，问答语义会在实现中漂移
- 若 OpenAPI 过早生成，后续字段命名调整会扩大变更面

## 8. Exit Criteria

当以下条件满足时，本计划可视为完成：

1. `schema / shared-types / API contract / 状态机` 已有稳定冻结文档
2. active handoff/status 已切到 implementation freeze 节点
3. 下一步可以明确进入实现 lane，而不是继续讨论方向

## 9. Decision Log

- `2026-04-08`: `Phase 2A` planning/design 阶段关闭，切换到独立 implementation freeze exec plan
- `2026-04-08`: `Phase 2A Contract Freeze` 已建立为主线程冻结事实源
