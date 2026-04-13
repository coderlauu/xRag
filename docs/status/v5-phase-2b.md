# v5 / Phase 2B Status

## 1. Metadata

- `version`: `v5`
- `phase`: `Phase 2B`
- `status`: `in-progress`
- `owner`: `codex`
- `updated_at`: `2026-04-13`

## 2. Goal

### In Scope

- 在 `Phase 2A` 可信问答闭环完成后，完成 `Phase 2B` 正式 contract freeze、implementation freeze，并进入 implementation lanes。
- 扩展范围控制：`标签 / 来源类型 / 时间范围`
- 检索实验台增强：`lexical / semantic / rerank` 贡献、未入答原因、URL state
- 评估与运维面板增强：`recall / groundedness / citation coverage / latency / cost / backlog`
- 证据包增强：claim grouping、多引用组合、freshness 提示强化
- 问题历史与继续提问入口

### Out Of Scope

- 直接进入实现 lane 并修改 `Phase 2A` 已冻结 contract
- 开放互联网联网回答
- 浏览器插件
- 团队协作 / 权限模型
- 移动端
- 单文档摘要、多文档对比摘要、相关内容推荐
- 多模型路由与更细粒度模型策略

## 3. Milestones

- `product-freeze`: `completed`
- `technical-evaluation`: `completed`
- `contract-freeze`: `completed`
- `implementation-freeze`: `completed`
- `implementation-lanes`: `in-progress`
- `testing-and-release-readiness`: `not-started`

## 4. Current Node

- `now`: 已完成 `Lane 0` 与 `Lane B`。当前代码事实源已覆盖 `schema + migrations + shared-types + answers DTO/controller/service/repository + OpenAPI + API client + web API adapter`，并且 `worker` 已对齐 `typed scope filters / answer_claims / claim-level citations / low_support exclusion / retrieval exclusion normalization`。
- `next`: 继续完成 `Lane A` 的 API read model 与 history surface；`Lane C / D` 在 `Lane A` 合流到稳定 shape 后启动。

## 5. Blockers

- `blocker`: 无
  - `impact`: 无
  - `owner`: 无

## 6. Validation

- `latest_validation`: `2026-04-13` 已完成 `Lane 0` 与 `Lane B` 本地验证；`git diff --check`、`pnpm --filter @xrag/worker typecheck`、`pnpm --filter @xrag/worker test:unit`、`pnpm test:integration` 通过；当前稳定 main CI 仍以 `24297811958` 为最近已知成功基线
- `result`: `passed`
- `latest_failure`: `none`

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v5.md](/Users/coderlauu/xRag/docs/handoff/v5.md)
- `active_exec_plan`: [Phase 2B implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-13-phase-2b-implementation-lanes.md)
- `prd`: [Phase 2B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-prd.md)
- `product_backlog`: [Phase 2B backlog](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-backlog.md)
- `interaction_delta`: [v5 interaction delta](/Users/coderlauu/xRag/design/spec/2026-04-11-v5-interaction-delta.md)
- `technical_tradeoffs`: [Phase 2B P0 technical tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-11-phase-2b-p0-technical-tradeoffs.md)
- `technical_docs`: [Phase 2B contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-13-phase-2b-contract-freeze.md), [Phase 2B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-12-phase-2b-architecture.md), [Phase 2B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-12-phase-2b-data-model.md), [Phase 2B api design](/Users/coderlauu/xRag/tech/api/2026-04-12-phase-2b-api.md), [Phase 2B contract freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-12-phase-2b-contract-freeze-prerequisites.md)
- `upstream_version`: [v4.md](/Users/coderlauu/xRag/docs/handoff/v4.md), [v4-phase-2a.md](/Users/coderlauu/xRag/docs/status/v4-phase-2a.md)
- `upstream_product_docs`: [Phase 2A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-prd.md), [Phase 2A backlog](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-backlog.md), [v4 interaction spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v4-interaction-spec.md), [Phase 2A evaluation plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
- `tech_docs`: [Phase 2A architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-2a-architecture.md), [Phase 2A runtime contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md), [Phase 2A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md), [Phase 2A data model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-2a-data-model.md), [Phase 2A api design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-2a-api.md)
- `exec_plans`: [Phase 2B implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-13-phase-2b-implementation-lanes.md), [Phase 2B implementation freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-13-phase-2b-implementation-freeze.md), [Phase 2B technical evaluation and contract freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-12-phase-2b-technical-evaluation-and-contract-freeze.md), [Phase 2B planning and design](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-11-phase-2b-planning-and-design.md), [Phase 2A implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-08-phase-2a-implementation-lanes.md), [Phase 2A implementation freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-08-phase-2a-implementation-freeze.md)
- `key_commits`: `66767af`, `b0a2bb9`, `717ae07`, `e16726c`, `2949dd0`
- `latest_ci_run`: `24297811958`（success，docs(phase-2b): add technical evaluation baseline）
