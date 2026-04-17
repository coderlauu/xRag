# Phase 3A Implementation Lanes

## 1. Metadata

- `plan_id`: `phase-3a-implementation-lanes`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [v7 handoff](/Users/coderlauu/xRag/docs/handoff/v7.md), [v7 status](/Users/coderlauu/xRag/docs/status/v7-phase-3a.md), [Phase 3A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md), [Phase 3A implementation freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-17-phase-3a-implementation-freeze.md)

## 2. Objective

在 `Phase 3A / P0` 已完成 contract freeze 和 implementation freeze 后，正式进入代码开发阶段；先由主线程把冻结 contract 落成代码 source-of-truth，再按明确写入边界拆出 API diagnostic read model、replay aggregation、web ops diagnostics 和 testing lanes。

## 3. Main Thread First

进入并行编码前，主线程必须先完成 `Lane 0: Contract To Code`。

`Lane 0` 当前状态：`not-started`

主线程写入范围固定为：

1. `packages/shared-types/src/index.ts`
2. `apps/api/src/ops/ops.dto.ts`
3. `apps/api/src/ops/ops.controller.ts`
4. `apps/api/src/ops/ops.service.ts`
5. `apps/api/src/openapi.ts`
6. `docs/generated/openapi/phase-2a-api.json`
7. `packages/api-client/src/index.ts`
8. `apps/web/src/lib/api.ts`

若 implementation 期间证明必须追加 index，只允许主线程在回写技术文档后处理：

1. `apps/api/src/database/schema.ts`
2. `apps/api/src/database/migrations/*`
3. `apps/api/src/database/migrations/meta/*`

主线程必须把以下 contract 落成代码事实源：

1. `OpsDiagnosticSampleKind / OpsDiagnosticOrigin / OpsRegressionClass / OpsReplayFreshnessFlag`
2. `OpsDiagnosticSample / OpsDiagnosticSampleListQuery / OpsDiagnosticSampleListResponse`
3. `OpsAnswerSessionReplayResponse / OpsDocumentReplayResponse / OpsDeploymentCompareResponse`
4. `GET /api/v1/ops/samples`
5. `GET /api/v1/ops/replays/answer-sessions/:sessionId`
6. `GET /api/v1/ops/replays/documents/:documentId`
7. `GET /api/v1/ops/deployments/compare`
8. `fetchOpsDiagnosticSamples / fetchOpsAnswerSessionReplay / fetchOpsDocumentReplay / fetchOpsDeploymentCompare`

`Lane 0` 完成前，不得启动子 agent 并行实现。`Lane 0` 完成后，后续可按本计划启动 `Lane A / Lane B`；任何 lane 触碰 schema、shared-types、DTO、OpenAPI 或 API client contract 时必须回到主线程。

## 4. Lanes

### Lane 0: Main Thread Contract To Code

- 类型：主线程
- 当前状态：`not-started`
- 目标：把 [Phase 3A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md) 映射到 shared-types、DTO、controller、OpenAPI、API client 和 web API adapter
- 写入范围：
  - `packages/shared-types/src/index.ts`
  - `apps/api/src/ops/ops.dto.ts`
  - `apps/api/src/ops/ops.controller.ts`
  - `apps/api/src/ops/ops.service.ts`
  - `apps/api/src/openapi.ts`
  - `docs/generated/openapi/phase-2a-api.json`
  - `packages/api-client/src/index.ts`
  - `apps/web/src/lib/api.ts`
- 建议新增内部 helper skeleton：
  - `apps/api/src/ops/ops.diagnostic-samples.ts`
  - `apps/api/src/ops/ops.replays.ts`
  - `apps/api/src/ops/ops.deployment-compare.ts`
- 不得修改：
  - `apps/web/src/features/ops/pages/ops-page.tsx`
  - `apps/web/src/features/answers/pages/ask-page.tsx`
  - `apps/web/src/features/search/pages/search-page.tsx`
  - `apps/web/src/features/detail/pages/detail-page.tsx`
  - `apps/worker/**`
  - `.github/workflows/ci.yml`
- 完成定义：
  - shared-types 已导出全部 Phase 3A response/query types
  - DTO 与 controller 已包含四个新增只读 ops endpoints
  - OpenAPI 与 `packages/api-client` 已包含新增 endpoints
  - web API adapter 已导出新增 client wrapper
  - 现有 ops endpoints 保持兼容
  - 下游 lane 不需要再修改 API path、字段命名、状态枚举和主数据模型语义

### Lane A: API Diagnostic Samples And Deployment Compare

- 类型：可并行 lane，必须等 `Lane 0` 完成后启动
- 当前状态：`not-started`
- 目标：实现 `/ops/samples` 与 `/ops/deployments/compare` 的真实聚合逻辑
- 写入范围：
  - `apps/api/src/ops/ops.diagnostic-samples.ts`
  - `apps/api/src/ops/ops.deployment-compare.ts`
  - `apps/api/test/integration/ops.phase-3a.samples.integration.test.ts`
- 可读取但不得修改：
  - `packages/shared-types/src/index.ts`
  - `apps/api/src/ops/ops.dto.ts`
  - `apps/api/src/ops/ops.controller.ts`
  - `packages/api-client/src/index.ts`
  - `docs/generated/openapi/phase-2a-api.json`
- 不得修改：
  - schema / migration
  - API 路径
  - DTO 字段命名
  - OpenAPI generated artifact
- 完成定义：
  - `origin=trend` 返回从现有 runtime/ops 风险派生的 answer/document 样本
  - `origin=incident_cluster` 按 `cluster_key` 返回可调查样本
  - `origin=release_compare` 按 `deployment_record_id` 返回受影响样本
  - deployment compare 以 `deployment_records.id` 为 anchor，输出 before/after window 与 `OpsRegressionClass`
  - 空数据返回稳定空集合或 `unknown` 分类，不伪造 deployment/evaluation 事实

### Lane B: API Answer And Document Replay

- 类型：可并行 lane，必须等 `Lane 0` 完成后启动
- 当前状态：`not-started`
- 目标：实现 answer session replay 与 document pipeline replay
- 写入范围：
  - `apps/api/src/ops/ops.replays.ts`
  - `apps/api/test/integration/ops.phase-3a.replays.integration.test.ts`
- 可读取但不得修改：
  - `packages/shared-types/src/index.ts`
  - `apps/api/src/answers/**`
  - `apps/api/src/documents/**`
  - `apps/api/src/ops/ops.dto.ts`
  - `apps/api/src/ops/ops.controller.ts`
- 不得修改：
  - `AnswerSessionResponse`
  - `AnswerRetrievalTraceResponse`
  - `DocumentDetail`
  - `DocumentTimelineResponse`
  - `DocumentEvidenceResponse`
  - prompt、chain-of-thought 或 provider 原始请求暴露逻辑
- 完成定义：
  - answer replay 复用 session 与 retrieval facts，补充 `related_context.freshness_flags`
  - document replay 复用 document、timeline、evidence facts，补充 blocking/incident/deployment context
  - 不触发 retry、reindex、rerun 或任何写操作
  - 缺失 session/document 返回 `404`

### Lane C: Web Ops Diagnostics

- 类型：可并行 lane，建议等 `Lane A / B` API 合流后启动
- 当前状态：`not-started`
- 目标：把 `/ops` 从 governance overview 扩展为 diagnostic workflow，同时保持 Ask/Search/Detail 只做轻量 notice
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
  - Ask / Search / Detail 后端响应字段
  - 自动拦截问答、自动重跑、自动回滚行为
- 完成定义：
  - `/ops` 保留 overview/trends 首屏
  - 支持进入 sample list、answer replay、document replay、deployment compare
  - `sample_id / regression_class / freshness_flags` 全部来自 API，不由 Web 自行推断
  - 空数据、404、加载失败均有稳定 UI

### Lane D: Integration, E2E, Smoke

- 类型：测试 lane，建议在 `Lane A / B / C` 合流后启动
- 当前状态：`not-started`
- 目标：补齐 Phase 3A 的 integration / e2e / smoke 验证
- 写入范围：
  - `apps/api/test/integration/ops.integration.test.ts`
  - `apps/api/test/integration/ops.phase-3a.*.integration.test.ts`
  - `apps/web/e2e/**`
  - `scripts/run-e2e-smoke.sh`
  - 必要时更新 `scripts/run-api-integration.sh`
- 不得修改：
  - 生产代码 contract
  - schema / migration
  - OpenAPI
- 完成定义：
  - integration 覆盖 `/ops/samples`、两个 replay endpoint、`/ops/deployments/compare`
  - integration 覆盖缺参、非法 enum、分页、404、空数据
  - e2e 覆盖 `/ops` diagnostic workflow 最小可见路径
  - smoke 保持生产巡检入口稳定

## 5. Recommended Fan-Out

推荐实施顺序固定为：

1. `Lane 0`
2. `Lane A` 与 `Lane B`
3. `Lane C`
4. `Lane D`
5. `testing-and-release-readiness`

`Lane A / B / C / D` 只有在 `Lane 0` 完成后才允许使用子 agent 并行。`Lane C` 建议等 `Lane A / B` API read model 合流后启动，避免 Web 反向发明 contract。若任何 lane 需要改 schema、shared-types、DTO、OpenAPI 或 API client contract，必须暂停并切回主线程。

## 6. Ownership Rules

1. `packages/shared-types/src/index.ts`、`apps/api/src/ops/ops.dto.ts`、`apps/api/src/ops/ops.controller.ts`、`apps/api/src/openapi.ts`、`docs/generated/openapi/*`、`packages/api-client/src/index.ts`、`apps/web/src/lib/api.ts` 始终归主线程。
2. `apps/api/src/database/schema.ts` 与 migrations 当前不在 P0 默认写入范围；若确需 additive index，必须由主线程先回写技术文档。
3. 子 agent 不允许擅自修改 API path、字段命名、状态枚举和主数据模型语义。
4. `diagnostic_sample` 是 API read model，不得新增 `diagnostic_samples` 表。
5. `new_regression / existing_debt / unknown` 是 compare response 分类，不得写成 DB enum 或自动 rollback 条件。
6. replay 不得暴露 prompt、chain-of-thought 或 provider 原始请求。
7. Ask / Search / Detail 仍只消费轻量 `OpsGovernanceNotice`，不得承载 replay payload。

## 7. Validation

阶段性验证：

- `git diff --check`
- `pnpm docs:check`
- `pnpm --filter @xrag/shared-types typecheck`
- `pnpm --filter @xrag/api typecheck`
- `pnpm --filter @xrag/api-client typecheck`
- `pnpm --filter @xrag/api openapi:generate`
- `pnpm contract:check`
- `pnpm test:integration`
- `pnpm --filter @xrag/web typecheck`
- `pnpm --filter @xrag/web build`
- `pnpm test:e2e`
- `pnpm e2e:smoke`

发布前验证：

- `pnpm validate`
- `pnpm test:unit`
- `pnpm test:integration`
- `pnpm test:e2e`
- `pnpm e2e:smoke`

## 8. Risks

- `docs/generated/openapi/phase-2a-api.json` 仍是当前 generated contract 检查入口；`Lane 0` 不应在同一次改动里重命名生成文件，避免扩大 CI 变更面。
- 如果 sample、replay、compare 全部堆进 `ops.service.ts`，并行开发会互相覆盖；应通过 `ops.diagnostic-samples.ts / ops.replays.ts / ops.deployment-compare.ts` 拆开。
- 如果 Web 先落 mock 规则，后端 contract 容易被 UI 反向牵引。
- 如果 compare 结果被写回主业务状态，会越过 `Phase 3A / P0` 只读边界。
- 若目标环境仍有历史 `not_indexed` 文档，先单独执行 `pnpm recovery:backfill-indexing -- --dry-run`，不要把 recovery 并入 diagnostics 代码 lane。

## 9. Exit Criteria

当以下条件满足时，本计划可视为完成：

1. `Lane 0` 已落地并成为实际代码事实源。
2. `Lane A / B / C / D` 已按边界完成并合流。
3. 当前活跃 exec plan 已切换到 `testing-and-release-readiness`。
4. status / handoff 已更新到新的 resume 节点。

## 10. Decision Log

- `2026-04-17`: `Phase 3A` implementation freeze 退出条件已满足，正式切换到 implementation lanes。
- `2026-04-17`: 主线程 ownership 固定为 `shared-types / DTO / controller / OpenAPI / API client / web api adapter`；schema 默认不进入 P0 写入范围。
- `2026-04-17`: 首批实现 lane 固定为 `Lane A / B / C / D`，且必须等 `Lane 0` 完成后才能启动。
