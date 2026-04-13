# Phase 2B Implementation Lanes

## 1. Metadata

- `plan_id`: `phase-2b-implementation-lanes`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [v5 handoff](/Users/coderlauu/xRag/docs/handoff/v5.md), [v5 status](/Users/coderlauu/xRag/docs/status/v5-phase-2b.md), [Phase 2B contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-13-phase-2b-contract-freeze.md), [Phase 2B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-12-phase-2b-architecture.md), [Phase 2B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-12-phase-2b-data-model.md), [Phase 2B api design](/Users/coderlauu/xRag/tech/api/2026-04-12-phase-2b-api.md), [Phase 2B implementation freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-13-phase-2b-implementation-freeze.md), [Phase 2A implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-08-phase-2a-implementation-lanes.md)

## 2. Objective

在 `Phase 2B / P0` 已完成 implementation freeze 后，正式进入代码开发阶段；先由主线程把冻结 contract 落成代码 source-of-truth，再按明确写入边界拆出 `api / worker / web / tests` 并行 lane。

## 3. Main Thread First

进入并行编码前，主线程必须先完成 `Lane 0`。

`2026-04-13` 更新：`Lane 0` 已完成，当前代码事实源已覆盖 `schema / migrations / shared-types / answers contract skeleton / OpenAPI / SDK / web api adapter`，并通过本地 typecheck 与 integration 验证。

主线程完成项如下：

1. `apps/api/src/database/schema.ts`
2. `apps/api/src/database/migrations/*`
3. `packages/shared-types/src/index.ts`
4. `apps/api/src/answers/answers.dto.ts`
5. `apps/api/src/answers/answers.controller.ts`
6. `apps/api/src/answers/answers.service.ts`
7. `apps/api/src/answers/answers.repository.ts`
8. `apps/api/src/openapi.ts`
9. `packages/api-client/src/index.ts`
10. `apps/web/src/lib/api.ts`

主线程还负责把以下语义落到代码事实源：

1. `ScopeFilterSet / continued_from_session_id / evidence_groups / retrieval summary`
2. `answer_claims / continued_from_session_id / exclusion_reason` 的 schema 与命名
3. `GET /api/v1/answers`、`GET /api/v1/answers/{id}`、`GET /api/v1/answers/{id}/retrieval` 的公开 contract skeleton
4. `citation / scope / history / freshness / retrieval exclusion` 的 answer-quality invariants

在 `Lane 0` 合并前，不启动子 agent 写代码。

## 4. Lanes

### Lane 0: Main Thread Contract To Code

- 类型：主线程
- 目标：把 freeze 文档映射到 schema、migration、shared types、API contract skeleton、OpenAPI 和 API client
- 写入范围：
  - `apps/api/src/database/schema.ts`
  - `apps/api/src/database/migrations/*`
  - `packages/shared-types/src/index.ts`
  - `apps/api/src/answers/answers.dto.ts`
  - `apps/api/src/answers/answers.controller.ts`
  - `apps/api/src/answers/answers.service.ts`
  - `apps/api/src/answers/answers.repository.ts`
  - `apps/api/src/openapi.ts`
  - `packages/api-client/src/index.ts`
  - `apps/web/src/lib/api.ts`
  - `docs/generated/openapi/*`
- 完成定义：
  - `schema / shared-types / API path / DTO / OpenAPI / SDK` 已在代码中冻结
  - 下游 lane 不再需要改 API path、字段命名、状态枚举和主数据模型语义

### Lane A: API Read Model And History Surface

- 类型：子 agent
- 目标：在 `Lane 0` 已补齐 contract skeleton 的前提下，继续完善 recent answers、session read model、scope summary 与 evidence group 的 API 读写逻辑
- 写入范围：
  - `apps/api/src/answers/answers.service.ts`
  - `apps/api/src/answers/answers.repository.ts`
  - `apps/api/src/answers/answers.module.ts`
- 可读取但不得修改：
  - `apps/api/src/answers/answers.dto.ts`
  - `packages/shared-types/src/index.ts`
  - `apps/api/src/database/schema.ts`
  - `packages/api-client/src/index.ts`
- 不得修改：
  - API 路径
  - DTO 字段命名
  - 状态枚举
  - OpenAPI

### Lane B: Worker Answer Orchestration

- 类型：子 agent
- 目标：实现 typed scope filters、follow-up lineage、claim persistence、retrieval summary 与 exclusion reason 归一化
- 写入范围：
  - `apps/worker/src/answers/**`
  - `apps/worker/src/retrieval/**`
  - `apps/worker/src/database/answer-repository.ts`
- 可读取但不得修改：
  - `packages/shared-types/src/index.ts`
  - `apps/api/src/database/schema.ts`
  - `apps/api/src/answers/answers.dto.ts`
- 不得修改：
  - `apps/worker/src/providers/**`
  - `apps/worker/src/jobs/document-processing.ts`
  - API 路径、schema enum 值、job payload 字段命名
- `2026-04-13` 更新：`Lane B` 已由主线程先行收口，`worker` 已完成 `typed scope filters / answer_claims / claim-level citations / low_support exclusion`，并通过 `@xrag/worker typecheck + test:unit + pnpm test:integration`

### Lane C: Ask Workspace And Recent History

- 类型：子 agent
- 目标：在问答工作台接入 typed scope、recent answers、continue asking 与 evidence groups
- 写入范围：
  - `apps/web/src/features/answers/**`
  - `apps/web/src/lib/answer-state.ts`
  - `apps/web/src/lib/answer-session-store.ts`
- 可读取但不得修改：
  - `apps/web/src/lib/api.ts`
  - `packages/api-client/src/index.ts`
- 不得修改：
  - `apps/web/src/features/search/**`
  - `apps/web/src/features/detail/**`
  - `apps/web/src/features/ops/**`

### Lane D: Retrieval Workbench And Detail Evidence

- 类型：子 agent
- 目标：把 retrieval summary、excluded reason、claim evidence 与 citation jumpback 接到搜索页和详情页
- 写入范围：
  - `apps/web/src/features/search/**`
  - `apps/web/src/features/detail/**`
  - `apps/web/src/lib/document-state.ts`
- 可读取但不得修改：
  - `apps/web/src/lib/api.ts`
  - `packages/api-client/src/index.ts`
- 不得修改：
  - `apps/web/src/features/answers/**`
  - `apps/web/src/features/ops/**`

### Lane E: API Integration

- 类型：子 agent
- 目标：补 answers history / evidence groups / retrieval summary / continue asking 的 integration tests
- 写入范围：
  - `apps/api/test/integration/**`
- 依赖：
  - `Lane 0` 与相关 feature lane 已合并到可运行状态
- 不得修改：
  - 应用生产代码

### Lane F: Web E2E And Smoke

- 类型：子 agent
- 目标：补 ask / history / retrieval / detail 的 e2e 与 smoke 路径
- 写入范围：
  - `apps/web/e2e/**`
  - `apps/web/playwright.config.ts`
  - `scripts/run-e2e-smoke.sh`
- 依赖：
  - `Lane C`、`Lane D` 已合并到可运行状态
- 不得修改：
  - 生产代码 contract

### Lane G: Ops Freeze-Late

- 类型：保留
- 目标：仅在 `P0` 主链合流后，视容量决定是否开启 `ops / P1-01`
- 当前状态：
  - 不进入首批 lane

## 5. Recommended Fan-Out

推荐实施顺序固定为：

1. `Lane 0`
2. `Lane A`、`Lane B`
3. `Lane C`、`Lane D`
4. `Lane E`
5. `Lane F`
6. 视容量决定是否开启 `Lane G`

## 6. Ownership Rules

1. 子 agent 不允许擅自修改 API path、字段命名、状态枚举和主数据模型语义
2. 若某个子 lane 发现 contract 漂移，必须切回主线程，不在实现里私自重定义
3. `apps/api/src/database/schema.ts`、`migrations/*`、`packages/shared-types/src/index.ts`、`apps/api/src/openapi.ts`、`packages/api-client/src/index.ts` 始终归主线程
4. `apps/web/src/lib/api.ts` 始终归主线程，避免 `answers / search / detail` 三方撞车
5. `apps/worker/src/database/answer-repository.ts` 与 `apps/worker/src/retrieval/**` 由同一 lane 持有，避免 retrieval trace 与 citation 写入语义分叉
6. `ops / P1-01` 在主链完成前不得抢占 `P0` 资源

## 7. Validation

- `git diff --check`
- `corepack pnpm docs:check`
- `pnpm --filter @xrag/api typecheck`
- `pnpm --filter @xrag/worker typecheck`
- `pnpm --filter @xrag/web typecheck`
- `pnpm test:integration`
- `pnpm test:e2e`
- `pnpm e2e:smoke`

## 8. Risks

- 若 `Lane 0` 不先把 contract 落到代码，后续所有 lane 都会漂移
- `apps/worker/src/database/answer-repository.ts`、`apps/web/src/lib/api.ts`、`packages/shared-types/src/index.ts` 都是热点文件，必须保持单一 owner
- 若先做 web 再做 evidence / retrieval summary 持久化，前端会被迫猜测后端 shape
- 若测试 lane 过早启动，会把还没冻结稳的行为误固化成基线

## 9. Exit Criteria

当以下条件满足时，本计划可视为完成：

1. `Lane 0` 已落地并成为实际代码事实源
2. `Lane A / B / C / D / E / F` 已按边界完成并合流
3. `Phase 2B / P0` 的 OpenAPI、SDK、integration、e2e 与 smoke 基线可统一验证
4. status / handoff / exec plan 已切到下一实现节点或收口节点

## 10. Decision Log

- `2026-04-13`: `Phase 2B` implementation freeze 退出条件已满足，正式切换到 implementation lanes
- `2026-04-13`: 主线程 ownership 固定为 `schema / migrations / shared-types / DTO / OpenAPI / API client / web api adapter`
- `2026-04-13`: 首批实现 lane 固定为 `Lane A / B / C / D`，测试 lane `Lane E / F` 仅在 feature lane 合流后启动
- `2026-04-13`: `Lane 0` 已落地，新增 `continued_from_session_id / answer_claims / retrieval summary / evidence_groups / GET /api/v1/answers` 的代码 contract，并完成 typecheck + integration 验证
- `2026-04-13`: `Lane B` 已落地，`worker` 已对齐 `typed scope filters / answer_claims / claim-level citations / low_support exclusion / retrieval exclusion normalization`，下一步集中推进 `Lane A`
