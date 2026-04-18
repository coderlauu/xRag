# Phase 3B Contract Freeze Prerequisites

**日期：** 2026-04-18
**版本：** `v8 / Phase 3B`
**状态：** completed
**对应文档：**
- [Phase 3B Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-architecture.md)
- [Phase 3B Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-18-phase-3b-data-model.md)
- [Phase 3B API Design](/Users/coderlauu/xRag/tech/api/2026-04-18-phase-3b-api.md)
- [Phase 3B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-prd.md)
- [Phase 3B P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-18-phase-3b-p0-technical-tradeoffs.md)

---

## 1. 目的

这份文档只回答一个问题：

- `Phase 3B` 现在是否可以进入 contract freeze

结论先写：

- 可以进入
- 当前没有新的硬 blocker
- 但 contract freeze 必须把下面这些事项一次性锁死

---

## 2. 已完成的前提

以下方向已经在技术评估中收敛：

1. `Phase 3B` 继续沿用 `v7` 的 ops diagnostic read models，不新造诊断影子数据。
2. `recovery_candidate` 已收敛为 read model，而不是新表。
3. `operator_recovery_action` 已收敛为 durable mutation fact，需要新增 action table。
4. `recovery_audit_entry` 首版由 action facts 查询生成，不单独建 audit table。
5. `rollback_plan` 是 read model / manual checklist，不执行 production rollback。
6. 现有 `documents/:documentId/retry` 与 `documents/:documentId/reindex` 不作为 `/ops` recovery 主路径，而是可被 action service 复用的内部执行能力。
7. Answer diagnostic rerun 默认只读；任何重跑 answer generation、改写 answer facts 或影响 citation/freshness/refusal 的能力必须先进入 `answer-quality-gate`。

---

## 3. Contract Freeze 必须锁定的事项

### 3.1 资源与路径

需要正式冻结：

- `GET /api/v1/ops/recovery/candidates`
- `POST /api/v1/ops/recovery/actions/preview`
- `POST /api/v1/ops/recovery/actions`
- `GET /api/v1/ops/recovery/actions/:actionId`
- `GET /api/v1/ops/recovery/actions/:actionId/audit`
- `GET /api/v1/ops/recovery/rollback-plan`

同时明确既有路径继续保留：

- `POST /api/v1/documents/:documentId/retry`
- `POST /api/v1/documents/:documentId/reindex`
- `GET /api/v1/jobs/:jobId`
- `GET /api/v1/ops/samples`
- `GET /api/v1/ops/replays/answer-sessions/:sessionId`
- `GET /api/v1/ops/replays/documents/:documentId`
- `GET /api/v1/ops/deployments/compare`

### 3.2 新 schema

需要正式冻结：

- 是否新增 `operator_recovery_actions`
- action table 字段
- idempotency key unique 约束
- active action reconciliation 所需索引
- 是否新增 DB enum，还是用 varchar + shared-types union 约束

建议 contract freeze 倾向：

- 新增 `operator_recovery_actions`
- 首版不新增 `recovery_audit_entries`
- 首版不新增 rollback table
- 首版不改 `document_parse_jobs`

### 3.3 新 shared types

至少需要冻结：

- `OpsRecoveryCandidateSourceType`
- `OpsRecoveryActionType`
- `OpsRecoveryTargetType`
- `OpsRecoveryRiskLevel`
- `OpsRecoveryRecommendationState`
- `OpsRecoveryActionStatus`
- `OpsRecoveryCandidate`
- `OpsRecoveryCandidateListQuery`
- `OpsRecoveryCandidateListResponse`
- `OpsRecoveryActionPreviewRequest`
- `OpsRecoveryActionPreviewResponse`
- `OpsRecoveryActionCreateRequest`
- `OpsRecoveryActionResponse`
- `OpsRecoveryActionAuditResponse`
- `OpsRollbackPlanQuery`
- `OpsRollbackPlanResponse`

### 3.4 状态机语义

contract freeze 必须写死：

- action active statuses
- action terminal statuses
- stuck / timeout 是否只通过 `failed + diagnosis_code` 表达
- queue failed / stalled / exhausted retries 如何映射 action terminal state
- 前端轮询停止条件和服务端 reconciliation owner

### 3.5 action type 与 target type

contract freeze 必须写死：

- `action_type`
  - `document_reindex`
  - `document_retry`
  - `answer_diagnostic_rerun`
- `target_type`
  - `document`
  - `answer_session`

同时明确不进入 P0：

- `production_rollback`
- `full_corpus_reindex`
- `model_route_change`
- `auto_remediate`

### 3.6 Answer Quality Gate 边界

contract freeze 必须明确：

1. `answer_diagnostic_rerun` 是否只读。
2. 是否允许写入 `answer_sessions`、`answer_claims`、`answer_citations` 或 retrieval facts。
3. 如果允许写入，必须先完成 `answer-quality-gate` 记录和 eval assumptions。

建议 freeze 默认：

- P0 不改写 answer facts。
- `answer_diagnostic_rerun` 只生成 recovery preview/audit，不替换用户可见 answer。

---

## 4. 当前仍需在 Freeze 中写清的点

这些不是 blocker，但必须在 freeze 文档里落成正式决定：

1. `operator_recovery_actions.status` 是否使用 DB enum。
2. `actor` 在没有正式用户模型前如何表达。
3. `preview_id` 是否 durable，还是只把 preview 内容写入 action create request。
4. `target_refs` 的 JSON shape。
5. `source_facts / before_facts / after_facts` 的最小字段集。
6. action reconciliation 的超时阈值。
7. rollback plan 的 confidence/missing evidence 字段口径。
8. production `not_indexed` dry-run 是否需要在 contract freeze 前执行并记录。

---

## 5. 推荐结论

建议下一步进入 `contract-freeze`，并按以下顺序推进：

1. 锁定 `operator_recovery_action` schema 与状态机。
2. 锁定 `ops/recovery` API 路径、request/response DTO 与 SDK client。
3. 锁定 candidate、preview、action、audit、rollback plan 的对象边界。
4. 锁定 action liveness/reconciliation 与前端 polling contract。
5. 明确 answer diagnostic rerun 的 answer-quality gate 边界。

在这个顺序下，`Phase 3B` 可以继续保持 human-in-the-loop recovery，而不是膨胀成自动 remediation 平台。

---

## 6. 完成记录

- `2026-04-18`: 已完成 Phase 3B technical evaluation 文档。
- `2026-04-18`: 已确认可以进入 `contract-freeze`。
