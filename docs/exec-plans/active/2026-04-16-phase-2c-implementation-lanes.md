# Phase 2C Implementation Lanes

## 1. Metadata

- `plan_id`: `phase-2c-implementation-lanes`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [v6 handoff](/Users/coderlauu/xRag/docs/handoff/v6.md), [v6 status](/Users/coderlauu/xRag/docs/status/v6-phase-2c.md), [Phase 2C contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-contract-freeze.md), [Phase 2C implementation freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-16-phase-2c-implementation-freeze.md)

## 2. Objective

在 `Phase 2C / P0` 已完成 contract freeze 和 implementation freeze 后，正式进入代码开发阶段；先由主线程把冻结 contract 落成代码 source-of-truth，再按明确写入边界拆出 API read model、fact ingestion、web ops board 和 tests lanes。

## 3. Main Thread First

进入并行编码前，主线程必须先完成 `Lane 0: Contract To Code`。

`Lane 0` 当前状态：`completed`

主线程完成项固定为：

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

主线程还负责把以下 contract 落成代码事实源：

1. `evaluation_runs / deployment_records` schema、migration 和 Drizzle exports
2. `OpsReadinessBlockingReason / OpsRecommendedActionCode / OpsReleaseGuardRiskLevel / OpsTrendWindow / OpsTrendSource / OpsTrendMetric`
3. `OpsOverviewResponse / OpsTrendsResponse`
4. `GET /api/v1/ops/overview`
5. `GET /api/v1/ops/trends?window=24h|7d|30d`
6. 保留既有 `/ops/health-summary / incidents / answer-summary / deployments/latest` 向后兼容
7. 不给 `AnswerSessionResponse / DocumentDetail / Search` 响应新增治理字段

`Lane 0` 已完成后，后续可按本计划启动 `Lane A / B`；任何 lane 触碰 schema、shared-types、DTO、OpenAPI 或 API client contract 时必须回到主线程。

## 4. Lanes

### Lane 0: Main Thread Contract To Code

- 类型：主线程
- 目标：把 [Phase 2C Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-contract-freeze.md) 映射到 schema、migration、shared-types、DTO、OpenAPI、API client 和 web API adapter
- 写入范围：
  - `apps/api/src/database/schema.ts`
  - `apps/api/src/database/migrations/*`
  - `apps/api/src/database/migrations/meta/*`
  - `packages/shared-types/src/index.ts`
  - `apps/api/src/ops/ops.dto.ts`
  - `apps/api/src/ops/ops.controller.ts`
  - `apps/api/src/ops/ops.service.ts`
  - `apps/api/src/openapi.ts`
  - `docs/generated/openapi/phase-2a-api.json`
  - `packages/api-client/src/index.ts`
  - `apps/web/src/lib/api.ts`
- 不得修改：
  - `apps/web/src/features/ops/pages/ops-page.tsx`
  - `apps/web/src/features/answers/pages/ask-page.tsx`
  - `apps/web/src/features/search/pages/search-page.tsx`
  - `apps/web/src/features/detail/pages/detail-page.tsx`
  - `apps/worker/**`
  - `.github/workflows/ci.yml`
- 实施例外：
  - `apps/worker/src/queue/constants.ts` 已做最小兼容修复：停止从 `@xrag/shared-types` 做运行期 value re-export，避免 `dist-integration` 直接加载 shared-types 源码 TS；队列名和 job name 字面量未改变。
- 完成定义：
  - schema / migration 已包含 `evaluation_runs / deployment_records`
  - shared-types 已导出全部 Phase 2C ops response types
  - `/ops/overview` 与 `/ops/trends` 已有 DTO、controller、service skeleton、OpenAPI 和 API client wrapper
  - 现有 ops endpoints 保持兼容
  - `pnpm --filter @xrag/api typecheck`
  - `pnpm --filter @xrag/api openapi:generate`
  - `pnpm contract:check`
  - `pnpm --filter @xrag/api test:integration`

### Lane A: API Read Model And Governance Aggregation

- 类型：可并行 lane，必须等 `Lane 0` 完成后启动
- 当前状态：`completed`
- 目标：实现 `/ops/overview` 与 `/ops/trends` 的真实聚合逻辑
- 写入范围：
  - `apps/api/src/ops/ops.service.ts`
  - 必要时新增 `apps/api/src/ops/*` 内部 helper
- 可读取但不得修改：
  - `apps/api/src/database/schema.ts`
  - `packages/shared-types/src/index.ts`
  - `apps/api/src/ops/ops.dto.ts`
  - `packages/api-client/src/index.ts`
  - `docs/generated/openapi/phase-2a-api.json`
- 不得修改：
  - schema / migration
  - API 路径
  - DTO 字段命名
  - OpenAPI generated artifact
- 完成定义：
  - readiness 由 `documents.index_status / citation_ready / indexed_at` 聚合
  - runtime quality 由 `answer_sessions / answer_citations` 聚合
  - evaluation quality 只由 `evaluation_runs` 聚合
  - incidents clusters 由既有 incident candidates 聚合，不新增 `incident_clusters` 表
  - release guard 优先读 `deployment_records`，缺失时不伪造成功发布历史

### Lane B: Deployment And Evaluation Fact Ingestion

- 类型：可并行 lane，必须等 `Lane 0` 完成后启动
- 当前状态：`completed`
- 目标：让 deployment / smoke / evaluation 事实可进入 `deployment_records / evaluation_runs`
- 写入范围：
  - `.github/workflows/ci.yml`
  - `scripts/record-deploy-evidence.sh`
  - `scripts/ops-inspect-ci-run.sh`
  - `deploy/env/production.env.example`
  - `deploy/env/staging.env.example`
  - 必要时新增 `scripts/*` 的只写入事实脚本
- 可读取但不得修改：
  - `apps/api/src/database/schema.ts`
  - `packages/shared-types/src/index.ts`
  - `apps/api/src/ops/ops.dto.ts`
- 不得修改：
  - 新增 `/api/v1/evals/*`
  - 自动 remediation
  - 自动 rollback
  - API response shape
- 完成定义：
  - deploy evidence 中的 `run_id / commit_sha / image tags / smoke_status` 可映射到 `deployment_records`
  - evaluation 事实以内部脚本或受控输入写入 `evaluation_runs`
  - 没有 evaluation run 时，`overview.evaluation_quality` 保持 `null`

### Lane C: Web Ops Board And Lightweight Notices

- 类型：可并行 lane，必须等 `Lane 0` 完成后启动
- 目标：把现有 `/ops` 页面升级为 Phase 2C 治理主板，并通过 `overview.notices` 给 Ask / Search / Detail 做轻量提示
- 写入范围：
  - `apps/web/src/features/ops/pages/ops-page.tsx`
  - `apps/web/src/features/answers/pages/ask-page.tsx`
  - `apps/web/src/features/search/pages/search-page.tsx`
  - `apps/web/src/features/detail/pages/detail-page.tsx`
  - 必要时新增 `apps/web/src/features/ops/**` 内部展示组件
- 可读取但不得修改：
  - `apps/web/src/lib/api.ts`
  - `packages/api-client/src/index.ts`
  - `packages/shared-types/src/index.ts`
- 不得修改：
  - API client contract
  - 后端 DTO
  - Ask / Search / Detail 后端响应字段
  - 自动拦截问答行为
- 完成定义：
  - `/ops` 使用 `fetchOpsOverview / fetchOpsTrends`
  - readiness、quality scorecard、incident clusters、release guard 和 recommended actions 均来自 API
  - Ask / Search / Detail 的 notice 只是提示与跳转，不改变提交、检索、引用或拒答 contract
  - `pnpm --filter @xrag/web typecheck`
  - `pnpm --filter @xrag/web build`

### Lane D: Integration, E2E, Smoke

- 类型：测试 lane，建议在 `Lane A / B / C` 合流后启动
- 目标：补齐 Phase 2C 的 integration / e2e / smoke 验证
- 写入范围：
  - `apps/api/test/integration/ops.integration.test.ts`
  - `apps/web/e2e/**`
  - `scripts/run-e2e-smoke.sh`
  - 必要时更新 `scripts/run-api-integration.sh`
- 不得修改：
  - 生产代码 contract
  - schema / migration
  - OpenAPI
- 完成定义：
  - integration 覆盖 `/ops/overview`、`/ops/trends`、旧 ops endpoints 兼容
  - e2e 覆盖 `/ops` 主板最小可见路径
  - smoke 保持生产巡检入口稳定

## 5. Recommended Fan-Out

推荐实施顺序固定为：

1. `Lane 0`
2. `Lane A` 与 `Lane B`
3. `Lane C`
4. `Lane D`
5. `testing-and-release-readiness`

`Lane A / B / C / D` 只有在 `Lane 0` 完成后才允许使用子 agent 并行。若任何 lane 需要改 schema、shared-types、DTO、OpenAPI 或 API client contract，必须暂停并切回主线程。

当前恢复点：`Lane 0`、`Lane A`、`Lane B` 与 `Lane C` 已完成，下一步优先启动 `Lane D: Integration, E2E, Smoke`；完成后再切到 `testing-and-release-readiness`。

## 6. Ownership Rules

1. `apps/api/src/database/schema.ts`、`migrations/*`、`packages/shared-types/src/index.ts`、`apps/api/src/openapi.ts`、`docs/generated/openapi/*`、`packages/api-client/src/index.ts`、`apps/web/src/lib/api.ts` 始终归主线程。
2. 子 agent 不允许擅自修改 API path、字段命名、状态枚举和主数据模型语义。
3. `runtime_quality.refusal_rate` 与 `evaluation_quality.refusal_precision` 不得混写。
4. `groundedness / recall_at_10 / mrr / hit_in_answer_rate` 只能来自 `evaluation_runs`。
5. readiness 不得改写 Ask 状态机，也不得把 `no_ready_documents` 包装成 provider failure。
6. release guard 只能给出推荐动作，不得自动回滚。

## 7. Validation

阶段性验证：

- `git diff --check`
- `pnpm docs:check`
- `pnpm --filter @xrag/api typecheck`
- `pnpm --filter @xrag/api openapi:generate`
- `pnpm contract:check`
- `pnpm --filter @xrag/api test:integration`
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
- 如果 `evaluation_runs` ingestion 与 `/ops/overview` read model 混在同一 lane，故障定位会变难。
- 如果 web 先落 mock 面板，后端 contract 容易被 UI 反向牵引。
- 如果 testing lane 过早启动，会把还没稳定的治理逻辑误固化成 e2e 基线。

## 9. Exit Criteria

当以下条件满足时，本计划可视为完成：

1. `Lane 0` 已落地并成为实际代码事实源。
2. `Lane A / B / C / D` 已按边界完成并合流。
3. 当前活跃 exec plan 已切换到 `testing-and-release-readiness`。
4. status / handoff 已更新到新的 resume 节点。

## 10. Decision Log

- `2026-04-16`: `Phase 2C` implementation freeze 退出条件已满足，正式切换到 implementation lanes。
- `2026-04-16`: 主线程 ownership 固定为 `schema / migrations / shared-types / DTO / OpenAPI / API client / web api adapter`。
- `2026-04-16`: 首批实现 lane 固定为 `Lane A / B / C / D`，且必须等 `Lane 0` 完成后才能启动。
- `2026-04-16`: `Lane 0` 已完成 contract-to-code 落地：新增 `evaluation_runs / deployment_records` schema 与 migration，导出 Phase 2C ops shared-types/DTO/OpenAPI/API client/web adapter，并保留既有 ops endpoints 兼容。
- `2026-04-16`: `Lane 0` 集成测试发现 worker constants 运行期 re-export 会在 `dist-integration` 加载 `@xrag/shared-types` 源码 TS；已在 worker constants 内本地定义运行期常量并用 shared-types union 做类型约束，不改变 queue/job contract。
- `2026-04-16`: `Lane 0` 已通过 `pnpm --filter @xrag/api typecheck`、`pnpm --filter @xrag/web typecheck`、`pnpm --filter @xrag/api-client typecheck`、`pnpm --filter @xrag/worker typecheck`、`pnpm --filter @xrag/api openapi:generate`、`pnpm --filter @xrag/web build`、`pnpm test:integration`。
- `2026-04-16`: `Lane A` 已完成真实治理聚合：`readiness` 由 `documents` 聚合，`runtime quality` 由 `answer_sessions / answer_citations` 聚合，`evaluation quality` 由 `evaluation_runs` 聚合，`incident clusters` 复用既有 incident candidates，`release guard` 优先读取 `deployment_records`。
- `2026-04-16`: `Lane A` 已通过 `git diff --check`、`pnpm --filter @xrag/api typecheck`、`pnpm test:integration`，且 `/ops/overview`、`/ops/trends` integration coverage 已补齐。
- `2026-04-16`: `Lane B` 已完成最小事实写入闭环：`record-deploy-evidence` 修正 smoke status 语义，CI smoke evidence 可通过 SSH tunnel + PostgreSQL `127.0.0.1:5432` 回环映射写入 `deployment_records`，并新增受控 `evaluation_runs` 写入脚本。
- `2026-04-16`: `Lane B` 已通过 shell / Node 静态检查与本地 Docker PostgreSQL 实跑落库验证。
- `2026-04-16`: `Lane C` 已完成治理主板 UI 合流：`/ops` 已消费 `fetchOpsOverview / fetchOpsTrends` 渲染 readiness、runtime/evaluation quality、incident clusters、release guard 与 recommended actions；Ask / Search / Detail 已接入 `overview.notices` 轻量提示，且未改变既有行为 contract。
- `2026-04-16`: `Lane C` 已通过 `git diff --check`、`pnpm --filter @xrag/web typecheck`、`pnpm --filter @xrag/web build`。
