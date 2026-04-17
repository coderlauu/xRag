# Phase 3A Implementation Freeze

## 1. Metadata

- `plan_id`: `phase-3a-implementation-freeze`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [v7 handoff](/Users/coderlauu/xRag/docs/handoff/v7.md), [v7 status](/Users/coderlauu/xRag/docs/status/v7-phase-3a.md), [Phase 3A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md), [Phase 3A api design](/Users/coderlauu/xRag/tech/api/2026-04-17-phase-3a-api.md), [Phase 3A data model](/Users/coderlauu/xRag/tech/data-model/2026-04-17-phase-3a-data-model.md), [Phase 3A architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-architecture.md), [Phase 3A implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-17-phase-3a-implementation-lanes.md)

## 2. Objective

把已冻结的 `Phase 3A` contract 转成可以进入 implementation lanes 的落地计划：明确写入边界、lane 顺序、测试层级、OpenAPI/SDK 生成点和集成风险。

## 3. Scope

### In Scope

- 拆分 `Contract To Code / API diagnostics read models / Web ops diagnostics / Testing + release readiness` 实现 lane
- 明确主线程必须先完成的 contract-to-code 写入范围
- 明确哪些 lane 可以在 contract-to-code 完成后并行
- 明确最小测试矩阵：
  - API integration
  - shared-types/API client contract
  - Web build/typecheck
  - E2E/smoke 如涉及页面可达性
- 明确 rollback 与兼容策略

### Out Of Scope

- 在 implementation freeze 阶段直接改代码
- 未完成写入边界前下放子 agent
- 修改已冻结路径、字段名、状态枚举和主数据模型语义
- 自动 remediation、自动 rollback、团队协作或日志平台

## 4. Assumptions

- `Phase 3A Contract Freeze` 已完成，路径和 DTO 方向不再临时更改
- 当前实现继续以 `v6 / Phase 2C` 绿态为运行基线
- 当前 `P0` 默认不新增 durable table
- `docs/generated/openapi/phase-2a-api.json` 仍是当前 generated contract 检查入口；`Phase 3A` 不在 implementation freeze 阶段重命名该文件

## 5. Main Thread First

进入代码实现后，主线程必须先完成 `Lane 0: Contract To Code`。在 `Lane 0` 完成前，不启动并行 implementation lanes。

`Lane 0` 独占以下上游 contract-to-code 文件：

1. `packages/shared-types/src/index.ts`
2. `apps/api/src/ops/ops.dto.ts`
3. `apps/api/src/ops/ops.controller.ts`
4. `apps/api/src/ops/ops.service.ts`
5. `apps/api/src/openapi.ts`
6. `docs/generated/openapi/phase-2a-api.json`
7. `packages/api-client/src/index.ts`
8. `apps/web/src/lib/api.ts`

若实现期间证明需要 additive index，以下文件也只能由主线程处理，且必须先回写技术文档：

1. `apps/api/src/database/schema.ts`
2. `apps/api/src/database/migrations/*`
3. `apps/api/src/database/migrations/meta/*`

主线程还必须先落地以下稳定语义：

1. `OpsDiagnosticSampleKind / OpsDiagnosticOrigin / OpsRegressionClass / OpsReplayFreshnessFlag`
2. `OpsDiagnosticSample / OpsDiagnosticSampleListResponse`
3. `OpsAnswerSessionReplayResponse / OpsDocumentReplayResponse / OpsDeploymentCompareResponse`
4. `GET /api/v1/ops/samples`
5. `GET /api/v1/ops/replays/answer-sessions/:sessionId`
6. `GET /api/v1/ops/replays/documents/:documentId`
7. `GET /api/v1/ops/deployments/compare`
8. `packages/api-client` 与 `apps/web/src/lib/api.ts` 的对应 wrapper

## 6. Lane Split

### Lane 0: Main Thread Contract To Code

- 类型：主线程
- 目标：把 [Phase 3A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md) 落到 shared-types、DTO、controller、OpenAPI、API client 和 web API adapter
- 写入范围：见第 5 节
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
  - shared-types 已导出 freeze 文档规定的全部 Phase 3A types
  - DTO 与 controller 已包含四个新增只读 ops endpoints
  - OpenAPI 与 `packages/api-client` 已包含新增 endpoints
  - web API adapter 已导出新增 client wrapper
  - 下游 lane 不需要再修改 API path、字段命名、状态枚举和主数据模型语义

### Lane A: API Diagnostic Samples And Deployment Compare

- 类型：可并行 lane，必须等 `Lane 0` 完成后启动
- 目标：实现 `GET /ops/samples` 与 `GET /ops/deployments/compare` 的真实 read model
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
  - `origin=trend` 可从 runtime/ops 风险派生 answer/document 样本
  - `origin=incident_cluster` 按 `cluster_key` 返回可调查样本
  - `origin=release_compare` 按 `deployment_record_id` 返回受影响样本
  - deployment compare 以 `deployment_records.id` 为 anchor，输出 before/after window 与 `OpsRegressionClass`
  - 空数据返回稳定空集合或 `unknown` 分类，不伪造 deployment/evaluation 事实

### Lane B: API Answer And Document Replay

- 类型：可并行 lane，必须等 `Lane 0` 完成后启动
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

## 7. Recommended Fan-Out

推荐实施顺序固定为：

1. `Lane 0`
2. `Lane A` 与 `Lane B`
3. `Lane C`
4. `Lane D`
5. `testing-and-release-readiness`

若后续使用子 agent 并行，只允许在 `Lane 0` 完成后拆 `Lane A / B`；`Lane C` 建议等 API read model 合流后启动，避免 Web 反向发明 contract。若任何 lane 需要改 schema、shared-types、DTO、OpenAPI 或 API client contract，必须暂停并切回主线程。

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

- 若 `Lane 0` 没先落地，API、SDK、Web 和测试会同时漂移。
- 若 sample list、replay、compare 都写在 `ops.service.ts` 单文件，会放大并行冲突；因此 implementation lanes 应优先使用内部 helper 文件隔离写入边界。
- 若 Web 先自行发明 sample 或 regression 规则，会破坏 `diagnostic_sample` 作为 API read model 的定位。
- 若 compare 把 `new_regression` 写成持久状态，会越过 Phase 3A P0 边界。
- 若旧环境仍存在历史 `not_indexed` 文档，应先单独执行 `pnpm recovery:backfill-indexing -- --dry-run`，不得把回补动作混入 Phase 3A diagnostics 实现。

## 10. Exit Criteria

本计划已满足以下退出条件：

1. `Lane 0` 文件 ownership 已固定。
2. 后续 `Lane A / B / C / D` 的写入范围、禁止项和启动顺序已固定。
3. 验证 gate 已固定。
4. 当前活跃 exec plan 已切换到 [Phase 3A Implementation Lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-17-phase-3a-implementation-lanes.md)。

## 11. Decision Log

- `2026-04-17`: `Phase 3A Contract Freeze` 已完成。
- `2026-04-17`: GitHub Actions run `24547237776` 已通过 `infra / validate / integration / e2e / build-images / deploy-staging / smoke-staging / deploy-production / smoke-production`。
- `2026-04-17`: implementation freeze 退出条件已满足，后续切换到 [Phase 3A Implementation Lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-17-phase-3a-implementation-lanes.md)。
