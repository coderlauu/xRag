# Phase 3B Implementation Freeze

## 1. Metadata

- `plan_id`: `phase-3b-implementation-freeze`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [v8 handoff](/Users/coderlauu/xRag/docs/handoff/v8.md), [v8 status](/Users/coderlauu/xRag/docs/status/v8-phase-3b.md), [Phase 3B contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-contract-freeze.md), [Phase 3B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-architecture.md), [Phase 3B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-18-phase-3b-data-model.md), [Phase 3B API design](/Users/coderlauu/xRag/tech/api/2026-04-18-phase-3b-api.md), [Phase 3B contract freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-18-phase-3b-contract-freeze-prerequisites.md), [Phase 3B contract freeze exec plan](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-contract-freeze.md)

## 2. Objective

把已冻结的 `Phase 3B` recovery contract 转成可执行的 implementation lane 计划：明确主线程写入边界、lane 顺序、并行条件、测试矩阵、rollback 点和 contract drift 防线。

本阶段已完成并归档；后续 [Phase 3B implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-implementation-lanes.md) 也已完成，当前进入 [Phase 3B release readiness](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-18-phase-3b-release-readiness.md)。

## 3. Scope

### In Scope

- 拆分 `Contract To Code / API recovery read models / Recovery action execution / Web ops recovery / Integration E2E Smoke` lanes
- 明确 `Lane 0` 必须由主线程先完成的 schema、shared-types、DTO、OpenAPI、API client 写入范围
- 明确 `Lane 0` 完成后哪些 API/Web/Test lane 可以并行
- 明确 answer diagnostic rerun 的只读实施边界
- 明确最小验证矩阵与 release-readiness 前置项

### Out Of Scope

- 在 implementation freeze 阶段直接改代码
- 未完成 `Lane 0` 前下放并行实现
- 修改已冻结的 API path、DTO 字段名、状态枚举、DB enum 或状态机语义
- 自动 remediation、自动 rerun、production rollback execution、团队权限或审批流
- 改写用户可见 answer facts、citation、freshness、refusal 或 retrieval ranking

## 4. Assumptions

- [Phase 3B Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-contract-freeze.md) 已完成，作为 implementation source of truth。
- `operator_recovery_actions` 是本轮唯一新增 durable mutation fact。
- Candidate、audit 和 rollback plan 不单独建表。
- `answer_diagnostic_rerun` 在 P0 中只读，不替换用户可见答案。
- `v7 / Phase 3A` ops diagnostic read models 保持上游事实源，不继续扩大 v7 scope。

## 5. Main Thread First

进入代码实现后，主线程必须先完成 `Lane 0: Contract To Code`。在 `Lane 0` 完成前，不启动并行 implementation lanes。

`Lane 0` 独占以下写入范围：

1. `apps/api/src/database/schema.ts`
2. `apps/api/src/database/migrations/*`
3. `apps/api/src/database/migrations/meta/*`
4. `packages/shared-types/src/index.ts`
5. `apps/api/src/ops/ops.dto.ts`
6. `apps/api/src/ops/ops.controller.ts`
7. `apps/api/src/ops/ops.service.ts`
8. `apps/api/src/openapi.ts`
9. `docs/generated/openapi/phase-2a-api.json`
10. `packages/api-client/src/index.ts`
11. `apps/web/src/lib/api.ts`

主线程必须先落地以下稳定语义：

1. `operator_recovery_action_type / target_type / status` DB enum
2. `operator_recovery_actions` table、unique idempotency key、status reconciliation index
3. `OpsRecovery*` shared types
4. `/api/v1/ops/recovery/*` DTO 和 controller path
5. API client wrappers 和 Web adapter wrappers
6. OpenAPI/generated contract

若实现期间发现 contract 无法落地，必须先回写 contract freeze 文档，不允许在实现中临时改字段名或状态语义。

## 6. Lane Split

### Lane 0: Main Thread Contract To Code

- 类型：主线程
- 目标：把 [Phase 3B Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-contract-freeze.md) 落到 schema、shared-types、DTO、controller、OpenAPI、API client 和 Web adapter
- 写入范围：见第 5 节
- 建议新增内部 skeleton：
  - `apps/api/src/ops/ops.recovery-candidates.ts`
  - `apps/api/src/ops/ops.recovery-actions.ts`
  - `apps/api/src/ops/ops.rollback-plan.ts`
- 不得修改：
  - `apps/web/src/features/ops/pages/ops-page.tsx`
  - `apps/worker/**`
  - answer orchestration behavior
  - document retry/reindex existing public contract
- 完成定义：
  - migration/schema 具备 `operator_recovery_actions`
  - shared-types 导出全部 `OpsRecovery*` contract
  - DTO/controller/OpenAPI/API client 包含 frozen endpoints
  - Web adapter 可调用 recovery endpoints
  - 下游 lane 不需要再修改 API path、字段名、状态枚举或 generated contract

### Lane A: API Recovery Candidates, Preview, Rollback Plan

- 类型：可并行 lane，必须等 `Lane 0` 完成后启动
- 目标：实现 read-only candidate list、action preview 和 rollback plan
- 写入范围：
  - `apps/api/src/ops/ops.recovery-candidates.ts`
  - `apps/api/src/ops/ops.rollback-plan.ts`
  - `apps/api/test/integration/ops.phase-3b.recovery-read.integration.test.ts`
- 可读取但不得修改：
  - `packages/shared-types/src/index.ts`
  - `apps/api/src/ops/ops.dto.ts`
  - `apps/api/src/ops/ops.controller.ts`
  - `packages/api-client/src/index.ts`
  - generated OpenAPI
- 不得修改：
  - schema / migration
  - API path / DTO field names
  - action status enum
- 完成定义：
  - candidates 从 v7 diagnostic facts 派生，不写新表
  - preview 不创建 durable action
  - rollback plan 只输出 manual checklist，不执行 deploy/rollback
  - blocked preconditions 和 missing evidence 可稳定表达

### Lane B: API Recovery Action Execution And Reconciliation

- 类型：可并行 lane，必须等 `Lane 0` 完成后启动；若与 Lane A 共改 service 边界，主线程协调合流
- 目标：实现人工确认后的 action create/status/audit 与 liveness reconciliation
- 写入范围：
  - `apps/api/src/ops/ops.recovery-actions.ts`
  - `apps/api/src/ops/ops.service.ts`
  - `apps/api/test/integration/ops.phase-3b.recovery-actions.integration.test.ts`
  - 如需 repository helper，可新增 `apps/api/src/ops/ops.recovery-actions.repository.ts`
- 可读取但不得修改：
  - `apps/api/src/database/schema.ts`
  - migration files
  - `packages/shared-types/src/index.ts`
  - `apps/api/src/documents/**`
  - `apps/worker/**`
- 不得修改：
  - DB enum/status contract
  - document retry/reindex public API contract
  - answer generation/retrieval/citation behavior
- 完成定义：
  - action create 持久化 `operator_recovery_actions`
  - idempotency key 防重复执行
  - document retry/reindex 通过 action envelope 复用既有执行能力
  - active action 可由服务端 reconciliation 收口到 terminal status
  - audit 返回 source/preview/before/after/timeline/manual follow-up
  - `answer_diagnostic_rerun` 只读，不改写 answer facts

### Lane C: Web Ops Recovery Workflow

- 类型：可并行 lane，建议等 `Lane A / B` API 合流后启动
- 目标：把 `/ops` 从 diagnostic workflow 扩展为 recovery workflow
- 写入范围：
  - `apps/web/src/features/ops/pages/ops-page.tsx`
  - `apps/web/src/features/ops/components/**`
  - 如需要，可新增 `apps/web/src/features/ops/lib/**`
- 可读取但不得修改：
  - `apps/web/src/lib/api.ts`
  - `packages/api-client/src/index.ts`
  - `packages/shared-types/src/index.ts`
- 不得修改：
  - API client contract
  - 后端 DTO
  - Ask/Search/Detail 主链路响应字段
  - 自动 rerun 或 rollback behavior
- 完成定义：
  - `/ops` 支持 candidate list、preview、confirm action、status、audit、rollback plan
  - confirmation UI 必须显示 risk、preconditions、blast radius、reason input
  - status polling 只根据服务端 terminal status 停止
  - 空数据、blocked、failed、missing evidence 均有稳定 UI

### Lane D: Integration, E2E, Smoke

- 类型：测试 lane，建议在 `Lane A / B / C` 合流后启动
- 目标：补齐 Phase 3B integration / e2e / smoke 验证
- 写入范围：
  - `apps/api/test/integration/ops.phase-3b.*.integration.test.ts`
  - `apps/web/e2e/**`
  - `scripts/run-e2e-smoke.sh`
  - 必要时更新 `scripts/run-api-integration.sh`
- 不得修改：
  - 生产代码 contract
  - schema / migration
  - OpenAPI / API client
- 完成定义：
  - integration 覆盖 candidates、preview、create/status/audit、rollback plan
  - integration 覆盖 invalid enum、missing reason、idempotency duplicate、blocked precondition、404
  - E2E 覆盖 `/ops` recovery workflow 最小可见路径
  - smoke 保持生产巡检入口稳定

## 7. Recommended Fan-Out

推荐实施顺序固定为：

1. `Lane 0`
2. `Lane A` 与 `Lane B`
3. `Lane C`
4. `Lane D`
5. `testing-and-release-readiness`

只有 `Lane 0` 完成并通过 contract checks 后，才允许拆分 `Lane A / B`。若任何 lane 需要改 schema、shared-types、DTO、OpenAPI、API client 或状态机语义，立即暂停并切回主线程。

## 8. Validation Gate

进入 implementation lanes 后，最小验证 gate 固定为：

1. `git diff --check`
2. `pnpm docs:check`
3. `pnpm --filter @xrag/shared-types typecheck`
4. `pnpm --filter @xrag/api typecheck`
5. `pnpm --filter @xrag/api-client typecheck`
6. `pnpm --filter @xrag/api openapi:generate`
7. `pnpm contract:check`
8. `pnpm test:integration`
9. `pnpm --filter @xrag/web typecheck`
10. `pnpm --filter @xrag/web build`
11. `pnpm test:e2e`
12. `pnpm e2e:smoke`

阶段性要求：

1. `Lane 0` 至少通过第 1-8 项。
2. `Lane A / B` 至少通过第 1、2、4、8 项。
3. `Lane C` 至少通过第 1、2、9、10 项。
4. `Lane D` 必须补齐第 8、11、12 项。
5. 发布前必须通过 `pnpm validate / pnpm test:unit / pnpm test:integration / pnpm test:e2e / pnpm e2e:smoke`。

## 9. Risks

- 如果 `Lane 0` 未先落地，schema、DTO、OpenAPI、API client、Web adapter 会同时漂移。
- 如果 recovery action 状态机被 lane 自行扩展，前端轮询与服务端 reconciliation 会分叉。
- 如果 `answer_diagnostic_rerun` 在实现中改写 answer facts，会破坏 citation/freshness/refusal 信任边界。
- 如果 rollback plan 被实现成 mutation，会把 v8 扩成 production rollback 版本。
- 如果 production `not_indexed` 未 dry-run 就纳入 P0，会把一次性运维动作误做成产品功能。

## 10. Exit Criteria

- Lane split、写入范围和并行条件已冻结。
- `Lane 0` 文件所有权明确。
- API/Web/Worker/Test 下游 lane 均不需要再改上游 contract。
- Validation matrix 已记录到 v8 status / handoff。
- 下一步可以进入 `implementation-lanes`，且首个实施节点必须是 `Lane 0: Contract To Code`。

## 11. Decision Log

- `2026-04-18`: Phase 3B implementation freeze completed; implementation lanes may start with `Lane 0: Contract To Code`.
