# Phase 2A Implementation Lanes

## 1. Metadata

- `plan_id`: `phase-2a-implementation-lanes`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [v4 handoff](/Users/coderlauu/xRag/docs/handoff/v4.md), [v4 status](/Users/coderlauu/xRag/docs/status/v4-phase-2a.md), [Phase 2A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-prd.md), [Phase 2A backlog](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-backlog.md), [Phase 2A technical tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-p0-technical-tradeoffs.md), [Phase 2A runtime contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md), [Phase 2A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md), [Phase 2A architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-2a-architecture.md), [Phase 2A data model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-2a-data-model.md), [Phase 2A api design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-2a-api.md), [Phase 2A implementation freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-08-phase-2a-implementation-freeze.md)

## 2. Objective

在 `Phase 2A / P0` 已完成 implementation freeze 后，正式进入代码开发阶段；先由主线程把冻结 contract 落成代码 source-of-truth，再按明确写入边界拆出 `worker / web / ops / tests` 并行 lane。

## 3. Main Thread First

进入并行编码前，主线程必须先完成 `Lane 0`。

`2026-04-08` 更新：`Lane 0` 已落地并完成 `schema / migrations / shared-types / documents / answers / ops contract skeleton / OpenAPI / SDK` 的主线程冻结；`Lane A / C / E` 与 `Lane B / D` 也已完成并进入主线，当前已可按计划启动 `Lane F`、`Lane G`。

主线程完成项如下：

1. `apps/api/src/database/schema.ts`
2. `apps/api/src/database/migrations/*`
3. `packages/shared-types/src/index.ts`
4. `apps/api/src/documents/documents.controller.ts`
5. `apps/api/src/documents/documents.dto.ts`
6. `apps/api/src/documents/documents.service.ts`
7. `apps/api/src/documents/documents.repository.ts`
8. `apps/api/src/answers/*`
9. `apps/api/src/ops/ops.controller.ts`
10. `apps/api/src/ops/ops.dto.ts`
11. `apps/api/src/ops/ops.service.ts`
12. `apps/api/src/jobs/jobs.dto.ts`
13. `apps/api/src/app.module.ts`
14. `apps/api/src/queue/queue.constants.ts`
15. `apps/api/src/queue/queue.service.ts`
16. `apps/api/src/openapi.ts`
17. `packages/api-client/src/index.ts`
18. `apps/web/src/lib/api.ts`

主线程还负责把以下语义落到代码事实源：

1. `citation / scope / diagnosis / state machine`
2. `202 Accepted + session polling` 问答接口基线
3. `search_result` scope snapshot 结构
4. `document index_status / answer_session_status` 枚举与 JSON 字段命名
5. `documents/{id}/evidence`、`documents/{id}/reindex`、`answers/*`、`ops/answer-summary` 的公开 contract skeleton
6. `document-indexing` 与 `answer-orchestration` 的 job payload discriminator

在 `Lane 0` 合并前，不启动子 agent 写代码。

## 4. Lanes

### Lane 0: Main Thread Contract To Code

- 类型：主线程
- 目标：把 freeze 文档映射到 schema、migration、shared types、API contract skeleton、OpenAPI 和 API client
- 写入范围：
  - `apps/api/src/database/schema.ts`
  - `apps/api/src/database/migrations/*`
  - `packages/shared-types/src/index.ts`
  - `apps/api/src/documents/documents.controller.ts`
  - `apps/api/src/documents/documents.dto.ts`
  - `apps/api/src/documents/documents.service.ts`
  - `apps/api/src/documents/documents.repository.ts`
  - `apps/api/src/answers/*`
  - `apps/api/src/ops/ops.controller.ts`
  - `apps/api/src/ops/ops.dto.ts`
  - `apps/api/src/ops/ops.service.ts`
  - `apps/api/src/jobs/jobs.dto.ts`
  - `apps/api/src/app.module.ts`
  - `apps/api/src/queue/queue.constants.ts`
  - `apps/api/src/queue/queue.service.ts`
  - `apps/api/src/openapi.ts`
  - `packages/api-client/src/index.ts`
  - `apps/web/src/lib/api.ts`
  - `docs/generated/openapi/*`
- 完成定义：
  - `schema / shared-types / API DTO / queue payload / API path` 已在代码中冻结
  - `Phase 2A` 的 OpenAPI 基线已生成
  - 下游 lane 不再需要改 API path、字段命名、状态枚举和主数据模型语义

### Lane A: Worker Runtime Topology

- 类型：子 agent
- 目标：把 worker 从单队列单拓扑升级到三队列 runtime glue
- 写入范围：
  - `apps/worker/src/worker/bootstrap.ts`
  - `apps/worker/src/queue/constants.ts`
  - `apps/worker/src/queue/producer.ts`
  - `apps/worker/src/queue/*`
- 不得修改：
  - `apps/worker/src/database/*`
  - `apps/worker/src/jobs/document-processing.ts`
  - `apps/worker/src/providers/*`
  - `apps/worker/src/answers/*`

### Lane B: Document Indexing

- 类型：子 agent
- 目标：实现 `chunk_document / embed_document / reindex` 执行和索引状态推进
- 写入范围：
  - `apps/worker/src/indexing/**`
  - `apps/worker/src/jobs/*index*`
  - `apps/worker/src/database/*index*repository*.ts`
- 可读取但不得修改：
  - `apps/api/src/database/schema.ts`
  - `packages/shared-types/src/index.ts`
  - `apps/worker/src/worker/bootstrap.ts`
  - `apps/worker/src/database/repository.ts`
- 不得修改：
  - `apps/worker/src/jobs/document-processing.ts`
  - `apps/worker/src/providers/*`
  - `apps/worker/src/answers/*`
  - API 路径、schema enum 值、job payload 字段命名

### Lane C: AI Provider Adapters

- 类型：子 agent
- 目标：实现 `EmbeddingProvider / AnswerProvider` 与 OpenAI-compatible adapter
- 写入范围：
  - `apps/worker/src/providers/**`
- 依赖：
  - `Lane 0` 已冻结 provider contract 与 env 命名
- 不得修改：
  - queue 拓扑
  - repository 写入逻辑
  - answer state machine

### Lane D: Answer Orchestration

- 类型：子 agent
- 目标：实现 hybrid retrieval、candidate pack、citation validation 和 answer state progression
- 写入范围：
  - `apps/worker/src/answers/**`
  - `apps/worker/src/retrieval/**`
  - `apps/worker/src/database/*answer*repository*.ts`
- 依赖：
  - `Lane 0` 已冻结 `answer_session_status / scope / citation / retrieval trace`
  - `Lane A`、`Lane C` 已完成 queue glue 与 provider adapter
- 不得修改：
  - provider 实现
  - queue topology
  - 现有 parse / OCR / link 处理链

### Lane E: Ask Workspace

- 类型：子 agent
- 目标：新增问答入口、answer session 轮询、citation 卡片与状态展示
- 写入范围：
  - `apps/web/src/app/router.tsx`
  - `apps/web/src/shell/app-shell.tsx`
  - `apps/web/src/features/answers/**`
  - `apps/web/src/lib/answer-*`
- 不得修改：
  - `apps/web/src/features/search/**`
  - `apps/web/src/features/detail/**`
  - `apps/web/src/features/ops/**`
  - `packages/api-client/src/index.ts`

### Lane F: Search And Detail Freshness

- 类型：子 agent
- 目标：把 `search_result` scope snapshot、`evidence / reindex / index readiness / citation jumpback` 接到搜索页和详情页
- 写入范围：
  - `apps/web/src/features/search/**`
  - `apps/web/src/features/detail/**`
  - `apps/web/src/lib/document-state.ts`
- 不得修改：
  - `apps/web/src/app/router.tsx`
  - `apps/web/src/shell/app-shell.tsx`
  - `apps/web/src/features/ops/**`
  - `packages/api-client/src/index.ts`

### Lane G: Ops Answer Summary

- 类型：子 agent
- 目标：把 `ops/answer-summary` 接到后端聚合和前端 ops 页面
- 写入范围：
  - `apps/api/src/ops/**`
  - `apps/web/src/features/ops/**`
- 依赖：
  - `Lane 0` 已冻结 `ops/answer-summary` 字段与聚合语义
- 不得修改：
  - `apps/web/src/features/answers/**`
  - `apps/web/src/features/search/**`
  - `apps/web/src/features/detail/**`

### Lane H: API Integration

- 类型：子 agent
- 目标：补 `answers / evidence / reindex / ops/answer-summary` 的 integration tests
- 写入范围：
  - `apps/api/test/integration/**`
- 依赖：
  - `Lane 0` 与相关 feature lane 已合并到可运行状态
- 不得修改：
  - 应用生产代码

### Lane I: Web E2E

- 类型：子 agent
- 目标：补 ask / search / detail / ops 的 e2e / smoke 路径
- 写入范围：
  - `apps/web/e2e/**`
  - `apps/web/playwright.config.ts`
  - `scripts/run-e2e-smoke.sh`
- 依赖：
  - `Lane E`、`Lane F`、`Lane G` 已合并到可运行状态

## 5. Recommended Fan-Out

推荐的实施顺序固定为：

1. `Lane 0` 已完成
2. `Lane A`、`Lane C`、`Lane E` 已完成并合流
3. 在 runtime glue 与 provider adapter 稳定后，`Lane B`、`Lane D` 已完成并合流
4. 在 API client 与关键 handler 可用后，启动 `Lane F`、`Lane G`
5. `Lane E / F / G` 合流后，再启动 `Lane H` 与 `Lane I`
6. 主线程统一做 OpenAPI / SDK 再生成、集成验证、状态回写和文档收口

## 6. Ownership Rules

1. 子 agent 不允许擅自修改 API path、字段命名、状态枚举和主数据模型语义
2. 若某个子 lane 发现 contract 漂移，必须切回主线程，不在实现里私自重定义
3. `apps/api/src/database/schema.ts`、`migrations/*`、`packages/shared-types/src/index.ts`、`apps/api/src/openapi.ts`、`packages/api-client/src/index.ts` 始终归主线程
4. `apps/api/src/answers/*`、`apps/api/src/documents/*`、`apps/api/src/ops/*` 的公开 contract skeleton 先由主线程铺好，再允许下游只改内部实现
5. `apps/web/src/lib/api.ts` 由主线程预铺，避免 `W1 / W2 / O1` 三方撞车
6. 若多个 worker lane 同时需要调整 queue payload，主线程统一改 `queue.service.ts` 与 `apps/worker/src/queue/constants.ts`，禁止双写

## 7. Validation

- `git diff --check`
- `corepack pnpm docs:check`
- `pnpm --filter @xrag/api typecheck`
- `pnpm --filter @xrag/worker typecheck`
- `pnpm --filter @xrag/web typecheck`
- `apps/api` integration tests
- `apps/web` e2e / smoke

## 8. Risks

- 若主线程没有先把 `schema / shared-types / API skeleton / OpenAPI` 落到代码，子 lane 会同时漂移多套 contract
- `apps/worker/src/worker/bootstrap.ts`、`apps/worker/src/queue/producer.ts`、`apps/worker/src/database/repository.ts` 是 worker 侧热点文件，必须避免多 lane 同改
- `apps/web/src/lib/api.ts`、`apps/web/src/app/router.tsx`、`apps/web/src/lib/document-state.ts` 是前端侧热点文件，必须保持单一 owner
- 若 answer orchestration 早于 citation validator 和 scope snapshot 落地，会直接破坏可追溯性
- 若测试 lane 过早启动，会把还没冻结的行为误固化成基线

## 9. Exit Criteria

当以下条件满足时，本计划可视为完成：

1. `Lane 0` 已落地并成为实际代码事实源
2. `Lane A / B / C / D / E / F / G / H / I` 已按边界完成并合流
3. `Phase 2A / P0` 的 OpenAPI、SDK、integration、e2e 与评估基线可统一验证
4. status / handoff / exec plan 已切到下一实现节点或收口节点

## 10. Decision Log

- `2026-04-08`: `Phase 2A` implementation freeze 退出条件已满足，正式切换到 implementation lanes
- `2026-04-08`: explorer review 后，主线程 ownership 扩大到 `documents / answers / ops` 的 contract skeleton、`openapi / api-client / web api adapters`，并将下游实现拆为 `worker-runtime / indexing / provider / answer / ask / search-detail / ops / integration / e2e`
- `2026-04-08`: `Lane 0` 已完成代码落地并通过 `@xrag/api`、`@xrag/api-client`、`@xrag/web` typecheck、`contract:check` 与 `apps/api` integration tests；下一批并行 lane 固定为 `Lane A`、`Lane C`、`Lane E`
- `2026-04-08`: `Lane A` 已通过 `dd22d54`、`452d68e` 合流，`Lane C` 已通过 `158c258` 合流，`Lane E` 已通过 `bdd2073` 合流；下一批并行 lane 固定为 `Lane B`、`Lane D`
- `2026-04-08`: `Lane B` 与 `Lane D` 已通过 `ca138aa` 合流，并补齐 `document-indexing`、`hybrid retrieval`、`citation persistence` 与 `answer-orchestration` 主链；下一批并行 lane 固定为 `Lane F`、`Lane G`
