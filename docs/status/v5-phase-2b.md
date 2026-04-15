# v5 / Phase 2B Status

## 1. Metadata

- `version`: `v5`
- `phase`: `Phase 2B`
- `status`: `completed`
- `owner`: `codex`
- `updated_at`: `2026-04-15`

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
- `implementation-lanes`: `completed`
- `testing-and-release-readiness`: `completed`

## 4. Current Node

- `now`: `Phase 2B` 已完成正式收口。`2026-04-15` 已修复文档长期停留 `not_indexed` 导致 Ask retrieval 为空的问题：`parse / OCR / link / manual text` 成功后会自动排入 `document-indexing`，并新增 `pnpm recovery:backfill-indexing` 作为既有旧文档的一次性回补入口；同日也已修复 GitHub Actions run `24460473108` 暴露的 web e2e 选择器漂移。
- `next`: 当前无 active exec plan。若继续推进，先在目标环境确认是否需要执行 `recovery:backfill-indexing`，或直接启动下一版本的 `handoff / status / exec plan` 脚手架。

## 5. Blockers

- `blocker`: 无
  - `impact`: 无
  - `owner`: 无

## 6. Validation

- `latest_validation`: `2026-04-15` 已完成本地定向修复验证：`pnpm --filter @xrag/worker test:unit`、`pnpm --filter @xrag/api build:test`、`node --test --test-concurrency=1 apps/api/dist-integration/apps/api/test/integration/documents.integration.test.js apps/api/dist-integration/apps/api/test/integration/uploads.integration.test.js apps/api/dist-integration/apps/api/test/integration/link-documents.integration.test.js`、`./scripts/run-e2e-smoke.sh`、`pnpm --filter @xrag/web typecheck` 通过；GitHub Actions run `24461689868` 已成功，`Phase 2B` 发布基线成立
- `result`: `passed`
- `latest_failure`: `GitHub Actions run 24460473108 failed in e2e because detail-page locator text made several getByText(title) assertions ambiguous under Playwright strict mode; fixed locally on 2026-04-15`

## 7. Linked Artifacts

- `current_handoff`: [current.md](/Users/coderlauu/xRag/docs/handoff/current.md)
- `version_handoff`: [v5.md](/Users/coderlauu/xRag/docs/handoff/v5.md)
- `active_exec_plan`: `none (Phase 2B completed)`
- `latest_completed_exec_plan`: [Phase 2B testing and release readiness](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-14-phase-2b-testing-and-release-readiness.md)
- `prd`: [Phase 2B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-prd.md)
- `product_backlog`: [Phase 2B backlog](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-backlog.md)
- `interaction_delta`: [v5 interaction delta](/Users/coderlauu/xRag/design/spec/2026-04-11-v5-interaction-delta.md)
- `technical_tradeoffs`: [Phase 2B P0 technical tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-11-phase-2b-p0-technical-tradeoffs.md)
- `technical_docs`: [Phase 2B contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-13-phase-2b-contract-freeze.md), [Phase 2B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-12-phase-2b-architecture.md), [Phase 2B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-12-phase-2b-data-model.md), [Phase 2B api design](/Users/coderlauu/xRag/tech/api/2026-04-12-phase-2b-api.md), [Phase 2B contract freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-12-phase-2b-contract-freeze-prerequisites.md)
- `upstream_version`: [v4.md](/Users/coderlauu/xRag/docs/handoff/v4.md), [v4-phase-2a.md](/Users/coderlauu/xRag/docs/status/v4-phase-2a.md)
- `upstream_product_docs`: [Phase 2A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-prd.md), [Phase 2A backlog](/Users/coderlauu/xRag/docs/prd/2026-04-07-xrag-phase-2a-backlog.md), [v4 interaction spec](/Users/coderlauu/xRag/design/spec/2026-04-07-v4-interaction-spec.md), [Phase 2A evaluation plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
- `tech_docs`: [Phase 2A architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-2a-architecture.md), [Phase 2A runtime contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md), [Phase 2A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md), [Phase 2A data model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-2a-data-model.md), [Phase 2A api design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-2a-api.md)
- `exec_plans`: [Phase 2B testing and release readiness](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-14-phase-2b-testing-and-release-readiness.md), [Phase 2B implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-13-phase-2b-implementation-lanes.md), [Phase 2B implementation freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-13-phase-2b-implementation-freeze.md), [Phase 2B technical evaluation and contract freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-12-phase-2b-technical-evaluation-and-contract-freeze.md), [Phase 2B planning and design](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-11-phase-2b-planning-and-design.md), [Phase 2A implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-08-phase-2a-implementation-lanes.md), [Phase 2A implementation freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-08-phase-2a-implementation-freeze.md)
- `key_commits`: `66767af`, `b0a2bb9`, `717ae07`, `e16726c`, `2949dd0`, `12b22fb`, `1bf8d27`, `e470270`, `c2df206`, `3ed3672`, `bb14372`
- `latest_ci_run`: `24461689868 success`
