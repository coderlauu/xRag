# Phase 2C Implementation Freeze Exec Plan

## 1. Metadata

- `plan_id`: `phase-2c-implementation-freeze`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: `docs/handoff/v6.md`, `docs/status/v6-phase-2c.md`, `tech/architecture/2026-04-16-phase-2c-contract-freeze.md`

## 2. Objective

在 `Phase 2C` contract 已冻结后，把实现顺序、文件 ownership、验证边界和可并行 lane 切分固定下来，为后续代码实现做准备。

## 3. Main Thread First

进入代码实现后，主线程必须先完成 `Lane 0: Contract To Code`。在 `Lane 0` 完成前，不启动并行 implementation lanes。

`Lane 0` 独占以下上游 contract-to-code 文件：

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

主线程还必须先落地以下稳定语义：

1. `evaluation_runs / deployment_records` schema 与迁移
2. `OpsOverviewResponse / OpsTrendsResponse` shared types
3. `GET /api/v1/ops/overview` 与 `GET /api/v1/ops/trends` DTO / OpenAPI / API client skeleton
4. `runtime_quality` 与 `evaluation_quality` 分区，不混写指标口径
5. readiness 只作为治理读面，不改写 `AnswerSessionStatus`
6. `Ask / Search / Detail` 不新增主 API contract 字段，轻量提示只读 `overview.notices`

## 4. Lane Split

### Lane 0: Main Thread Contract To Code

- 类型：主线程
- 目标：把 [Phase 2C Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-contract-freeze.md) 落到 schema、migration、shared-types、DTO、OpenAPI、API client 和 web API adapter
- 写入范围：见第 3 节
- 完成定义：
  - schema enum、表、索引与 migration 已生成并可迁移
  - shared-types 已导出 freeze 文档规定的全部类型
  - OpenAPI 与 `packages/api-client` 已包含新增 ops endpoints
  - 下游 lane 不需要再修改 API path、字段命名、状态枚举和主数据模型语义

### Lane A: API Read Model And Governance Aggregation

- 类型：可并行 lane，必须等 `Lane 0` 完成后启动
- 目标：实现 `/ops/overview` 与 `/ops/trends` 的真实聚合逻辑
- 写入范围：
  - `apps/api/src/ops/ops.service.ts`
  - 如确有必要，可新增 `apps/api/src/ops/*` 内部 helper，但不得新增公开 API path
- 可读取但不得修改：
  - `apps/api/src/database/schema.ts`
  - `packages/shared-types/src/index.ts`
  - `apps/api/src/ops/ops.dto.ts`
  - `packages/api-client/src/index.ts`
- 不得修改：
  - schema / migration
  - API 路径
  - DTO 字段命名
  - OpenAPI generated artifact

### Lane B: Deployment And Evaluation Fact Ingestion

- 类型：可并行 lane，必须等 `Lane 0` 完成后启动
- 目标：让 deployment / smoke / evaluation 事实可进入 `deployment_records / evaluation_runs`，供 release guard 与 quality scorecard 使用
- 写入范围：
  - `.github/workflows/ci.yml`
  - `scripts/record-deploy-evidence.sh`
  - `scripts/ops-inspect-ci-run.sh`
  - `deploy/env/production.env.example`
  - `deploy/env/staging.env.example`
  - 必要时新增 `scripts/*` 的只写入事实脚本
- 不得修改：
  - `apps/api/src/ops/ops.dto.ts`
  - `packages/shared-types/src/index.ts`
  - 新增 `/api/v1/evals/*`
  - 自动 remediation 或自动回滚逻辑

### Lane C: Web Ops Board And Lightweight Notices

- 类型：可并行 lane，必须等 `Lane 0` 完成后启动
- 目标：把现有 `/ops` 页面升级为 Phase 2C 治理主板，并按 `overview.notices` 在 Ask / Search / Detail 做轻量提示
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
  - Ask / Search / Detail 的后端响应字段
  - 自动拦截问答的行为

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

## 5. Recommended Fan-Out

推荐实施顺序固定为：

1. `Lane 0`
2. `Lane A` 与 `Lane B`
3. `Lane C`
4. `Lane D`
5. `testing-and-release-readiness`

若后续使用子 agent 并行，只允许在 `Lane 0` 完成后拆 `Lane A / B / C / D`，且每个子 agent 必须遵守本计划的写入范围。

## 6. Validation Gate

进入 implementation lanes 后，最小验证 gate 固定为：

1. `git diff --check`
2. `pnpm docs:check`
3. `pnpm --filter @xrag/api typecheck`
4. `pnpm --filter @xrag/api openapi:generate`
5. `pnpm contract:check`
6. `pnpm --filter @xrag/api test:integration`
7. `pnpm --filter @xrag/web typecheck`
8. `pnpm --filter @xrag/web build`
9. `pnpm test:e2e`
10. `pnpm e2e:smoke`

阶段性要求：

1. `Lane 0` 至少通过第 1-6 项。
2. `Lane C` 至少通过第 1、2、7、8 项。
3. `Lane D` 必须补齐第 6、9、10 项。
4. 发布前必须通过 `pnpm validate / pnpm test:integration / pnpm test:e2e / pnpm e2e:smoke`。

## 7. Risks

- 如果 `Lane 0` 没先落地，后续 web/API/test 会同时漂移。
- 如果 `runtime_quality` 与 `evaluation_quality` 混写，会破坏 Phase 2C 的核心口径。
- 如果 web 先自行发明 readiness 或 release guard 判断，会反向牵引后端 contract。
- 如果 ingestion lane 直接写自动回滚或自动回补，会越过 P0 边界。

## 8. Exit Criteria

本计划已满足以下退出条件：

1. `Lane 0` 文件 ownership 已固定。
2. 后续 `Lane A / B / C / D` 的写入范围、禁止项和启动顺序已固定。
3. 验证 gate 已固定。
4. 可以切换到 [Phase 2C Implementation Lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-16-phase-2c-implementation-lanes.md)。

## 9. Decision Log

- `2026-04-16`: `Phase 2C` contract freeze 已完成。
- `2026-04-16`: active exec plan 从 `contract-freeze` 切换到 `implementation-freeze`。
- `2026-04-16`: implementation freeze 退出条件已满足，后续已切换到 [Phase 2C Implementation Lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-16-phase-2c-implementation-lanes.md)。
