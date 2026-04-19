# Phase 3B Technical Evaluation

## 1. Metadata

- `plan_id`: `phase-3b-technical-evaluation`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [v8 handoff](/Users/coderlauu/xRag/docs/handoff/v8.md), [v8 status](/Users/coderlauu/xRag/docs/status/v8-phase-3b.md), [Phase 3B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-prd.md), [Phase 3B backlog](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-backlog.md), [v8 interaction delta](/Users/coderlauu/xRag/design/spec/2026-04-18-v8-interaction-delta.md), [Phase 3B tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-18-phase-3b-p0-technical-tradeoffs.md), [Phase 3B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-architecture.md), [Phase 3B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-18-phase-3b-data-model.md), [Phase 3B api design](/Users/coderlauu/xRag/tech/api/2026-04-18-phase-3b-api.md), [Phase 3B contract freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-18-phase-3b-contract-freeze-prerequisites.md), [Phase 3B planning and scope](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-planning-and-scope.md), [Phase 3A contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md), [Ask active session stuck polling retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-17-ask-active-session-stuck-polling-retrospective.md)

## 2. Objective

在 `v8 / Phase 3B` 已完成 product scope 后，对 `recovery candidate / operator-approved rerun / recovery execution audit / rollback plan / recovery action liveness` 进行正式技术评估，明确 source-of-truth、读写模型、API/schema/shared-types/queue 影响，以及是否可以进入 `contract-freeze`。

当前计划已完成并归档；后续 contract freeze、implementation freeze 与 [Phase 3B implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-implementation-lanes.md) 已完成，当前进入 [Phase 3B release readiness](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-18-phase-3b-release-readiness.md)。

## 3. Scope

### In Scope

- 审计现有 `apps/api / apps/worker / apps/web / packages/shared-types / packages/api-client` 中与 ops diagnostics、answers、documents、queue 和 deployment facts 相关的实现
- 产出 `architecture / data-model / api / contract-freeze-prerequisites` 第一版技术评估文档
- 判断 `recovery_candidate` 是 deterministic read model，还是需要 durable snapshot
- 判断 `operator_recovery_action` 与 `recovery_audit_entry` 是否需要新 table、状态机、queue job type、API path、OpenAPI 和 SDK 更新
- 判断 answer diagnostic rerun 是否触碰 retrieval、answer、citation、freshness、refusal 或 eval；若触碰，记录 `answer-quality-gate` 必要性
- 判断 production `not_indexed` recovery 是否应保持为 runbook/dry-run，还是可进入 v8 P0 product action
- 更新 `handoff / status / current / AGENTS`，保证新 session 可以直接恢复到 `technical-evaluation`

### Out Of Scope

- 直接修改 schema、shared-types、OpenAPI、API client 或生产代码
- 直接进入 `contract-freeze`、`implementation-freeze` 或 `implementation-lanes`
- 自动 remediation、自动 rerun、自动 rollback
- 团队权限、审批流、多模型治理或开放互联网联网回答

## 4. Assumptions

- `planning-and-scope` 已完成，第一版 `PRD / backlog / interaction delta / technical tradeoff` 已落盘
- `v7 / Phase 3A` 已完成 diagnostic read models 与 `/ops` workflow，并通过 release-readiness
- Ask active-session stuck polling 复盘中的 liveness 约束必须迁移到 recovery action 状态机
- 当前阶段主要输出仍是文档与冻结判断，不是实现代码

## 5. Risks

- 如果 candidate、action、audit、plan 的边界不清，后续 API/schema 会把 read model 和 mutation 混在一起
- 如果 operator action 没有 durable audit，rerun 会变成不可复盘的生产脚本按钮
- 如果 queue 对账和服务端终态收口没有设计清楚，recovery action 会重演永久 active 状态问题
- 如果 answer diagnostic rerun 触碰 answer/citation/freshness/refusal 却不走 `answer-quality-gate`，会破坏已冻结信任边界
- 如果 production `not_indexed` 未 dry-run 就纳入 P0，可能把一次性运维事项误做成产品功能

## 6. Plan

1. 审计现有 ops diagnostics、answers、documents、deployment、queue 和 worker 事实源，确认可复用边界。
2. 形成 `Phase 3B Architecture` 第一版，明确 recovery workflow、source-of-truth、liveness 和 gate。
3. 形成 `Phase 3B Data Model` 第一版，明确 candidate/action/audit/plan 是否需要 durable schema。
4. 形成 `Phase 3B API Design` 第一版，明确 read resources、mutation resources、OpenAPI/API client 影响。
5. 形成 `Phase 3B Contract Freeze Prerequisites`，列出进入 contract freeze 前必须回答的问题。
6. 在文档层判断是否可以进入 `contract-freeze`，但不在本阶段提前修改运行时代码。

## 7. Validation

- 单元测试：不适用，当前为文档与技术评估变更
- 集成测试：不适用，当前不改运行时代码
- E2E / smoke：不适用，当前不改运行时代码
- 文档阶段至少保证 `pnpm docs:check` 与 `git diff --check` 通过
- 若运行 production `not_indexed` dry-run，单独记录命令、环境和结果，不默认写入 P0

## 8. Rollback

- 若技术评估发现 `Phase 3B / P0` 需要超出 human-in-the-loop recovery 的权限、审批或自动化能力，则回退到 planning-and-scope，重新缩减 `P0`，而不是直接扩 schema 或并行实现。
- 若 answer diagnostic rerun 需要重新定义 answer/citation/freshness/refusal 口径，则先降 scope 或进入 `answer-quality-gate`，不直接实现。

## 9. Decision Log

- `2026-04-18`: `planning-and-scope` 已完成，`v8` 当前节点切换到 `technical-evaluation`。
- `2026-04-18`: 当前技术评估以“candidate 优先 read model、action/audit 倾向 durable facts、rollback 只做 manual plan”为初始结论。
- `2026-04-18`: 已形成 Phase 3B architecture、data model、API design 与 contract freeze prerequisites，并确认可以进入 `contract-freeze`。
