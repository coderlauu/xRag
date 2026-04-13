# Phase 2B Implementation Freeze

## 1. Metadata

- `plan_id`: `phase-2b-implementation-freeze`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [v5 handoff](/Users/coderlauu/xRag/docs/handoff/v5.md), [v5 status](/Users/coderlauu/xRag/docs/status/v5-phase-2b.md), [Phase 2B contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-13-phase-2b-contract-freeze.md), [Phase 2B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-12-phase-2b-architecture.md), [Phase 2B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-12-phase-2b-data-model.md), [Phase 2B api design](/Users/coderlauu/xRag/tech/api/2026-04-12-phase-2b-api.md), [Phase 2B contract freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-12-phase-2b-contract-freeze-prerequisites.md), [Phase 2A implementation freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-08-phase-2a-implementation-freeze.md), [Phase 2A runtime contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)

## 2. Objective

在 `Phase 2B / P0` 已完成正式 contract freeze 后，把后续编码入口收敛到明确的主线程冻结顺序与 lane ownership，避免在实现中重新打开 `schema / shared-types / API / answer-quality` 语义。

## 3. Main Thread First

并行实现前，主线程必须先冻结并落地：

1. `schema + migration`
2. `shared-types`
3. `API DTO / OpenAPI / SDK`
4. `answer-quality invariants`
   - `scope`
   - `citation`
   - `retrieval exclusion`
   - `history lineage`

未完成这一步前，不启动 `worker / web / test` 并行 lane。

## 4. Deliverables

### Must Have

- [Phase 2B Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-13-phase-2b-contract-freeze.md) 作为主线程冻结基线
- `Phase 2B data model / api` 与 contract freeze 语义保持一致
- 当前 active exec plan 从技术评估切换到 implementation freeze

### Next Coding Entry

后续编码应按以下顺序进入：

1. `schema + migration`
2. `shared-types + DTO/OpenAPI`
3. `answer repo / api service / worker orchestration`
4. `web ask + history + retrieval + evidence wiring`
5. `integration / e2e / smoke`

## 5. Out Of Scope

- 直接开始并行实现
- 在 contract freeze 未落地到 shared-types 前提前生成新 OpenAPI
- 提前把 `ops / P1-01` 拉入主实现链
- 重新打开 `conversation_threads / memory assistant / explain rerun pipeline`

## 6. Validation

- `git diff --check`
- `corepack pnpm docs:check`
- 保留最近稳定 main CI 作为 release-ready 工程基线

## 7. Risks

- 若 schema 与 shared-types 不同步落地，后续 API、worker 与前端会同时返工
- 若先写 web 再定 evidence group / retrieval summary shape，UI 很容易绑死错误 contract
- 若先拆 lane 再处理 answer-quality invariants，后续会出现 citation 与 scope 语义漂移

## 8. Exit Criteria

当以下条件满足时，本计划可视为完成：

1. `schema / shared-types / API / answer-quality` 已有稳定编码入口与文件 ownership
2. active handoff/status 已切到 implementation freeze 节点
3. 下一步可以明确进入 implementation lanes，而不是继续讨论 freeze 边界

## 9. Decision Log

- `2026-04-13`: `Phase 2B Contract Freeze` 已建立为主线程冻结事实源
- `2026-04-13`: active exec plan 从 `technical-evaluation-and-contract-freeze` 切换到 `implementation-freeze`
