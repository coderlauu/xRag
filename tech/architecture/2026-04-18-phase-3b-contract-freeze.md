# xRag Phase 3B Contract Freeze

**日期：** 2026-04-18  
**版本：** `v8 / Phase 3B`  
**状态：** freeze-ready  
**适用范围：** `P0-01 ~ P0-04` human-in-the-loop ops recovery  
**对应文档：**
- [Phase 3B Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-architecture.md)
- [Phase 3B Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-18-phase-3b-data-model.md)
- [Phase 3B API Design](/Users/coderlauu/xRag/tech/api/2026-04-18-phase-3b-api.md)
- [Phase 3B Contract Freeze Prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-18-phase-3b-contract-freeze-prerequisites.md)
- [Phase 3B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-prd.md)
- [Phase 3B Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-backlog.md)
- [Phase 3A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md)
- [Ask Active Session Stuck Polling Retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-17-ask-active-session-stuck-polling-retrospective.md)

---

## 1. 文档目的

这份文档把 `Phase 3B / P0` 进入实现前必须稳定的 contract 固定下来：

1. recovery read model 与 mutation fact 边界
2. `operator_recovery_actions` schema
3. shared-types 枚举与 DTO
4. `/api/v1/ops/recovery/*` API contract
5. recovery action 状态机、幂等、queue 对账和轮询停止条件
6. answer diagnostic rerun 的 answer-quality 边界

后续实现若与本文冲突，以本文为准；如需偏离，必须先回写本文和相关 `tech/*` 文档。

---

## 2. Source Of Truth 顺序

`Phase 3B / P0` 的 contract source-of-truth 顺序固定为：

1. [Phase 3B Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-contract-freeze.md)
2. [Phase 3B Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-18-phase-3b-data-model.md)
3. [Phase 3B API Design](/Users/coderlauu/xRag/tech/api/2026-04-18-phase-3b-api.md)
4. [Phase 3B Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-architecture.md)
5. [Phase 3A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md)
6. `packages/shared-types/src/index.ts`
7. `apps/api/src/ops/ops.dto.ts`
8. `apps/api/src/ops/ops.controller.ts`
9. OpenAPI / API client / Web implementation

---

## 3. Scope Freeze

### 3.1 P0 能力

`Phase 3B / P0` 固定为四个能力：

1. `P0-01 Recovery Candidates`
   - 从 v7 diagnostic facts 派生下一步恢复候选
2. `P0-02 Operator-approved Rerun`
   - 人工确认后执行受控 rerun
3. `P0-03 Recovery Execution Audit`
   - 每次 operator action 都可审计
4. `P0-04 Guarded Rollback Planning`
   - 只输出 manual rollback checklist，不执行回滚

### 3.2 明确不进入 P0

`Phase 3B / P0` 不包含：

- automatic remediation
- automatic rerun
- production rollback execution
- full corpus reindex
- model route change
- team permission / approval workflow
- answer generation rewrite
- open internet answering

---

## 4. Read / Write Model Freeze

| 对象 | 类型 | 持久化结论 | 说明 |
| --- | --- | --- | --- |
| `recovery_candidate` | read model | 不建表 | 从 v7 diagnostic facts deterministic 派生 |
| `operator_recovery_action` | mutation fact | 新增表 | 人工确认后的生产操作事实 |
| `recovery_audit_entry` | query view | 不单独建表 | 首版由 action table 生成 |
| `rollback_plan` | read model | 不建表 | 基于 deployment compare 的 manual checklist |

冻结结论：

1. Candidate 不创建 durable action，也不进入 action status。
2. Preview 不创建 durable action，但 action create 必须带 `preview_id` 与 `idempotency_key`。
3. Operator action 是唯一 P0 mutation fact。
4. Rollback plan 永远不是 rollback execution。

---

## 5. Schema Freeze

### 5.1 新增 DB enum

沿用当前 Drizzle schema 中 `pgEnum` 的项目惯例，`Phase 3B` 新增以下 DB enum：

| enum | 值 |
| --- | --- |
| `operator_recovery_action_type` | `document_reindex`, `document_retry`, `answer_diagnostic_rerun` |
| `operator_recovery_target_type` | `document`, `answer_session` |
| `operator_recovery_action_status` | `queued`, `running`, `succeeded`, `failed`, `cancelled`, `blocked` |

冻结结论：

1. 不修改既有 `job_status`、`index_status`、`answer_session_status`。
2. 不新增 `previewed`、`stuck`、`timed_out` action status。
3. Timeout/stuck/exhausted retries 通过 `failed + diagnosis_code` 表达。
4. `deployment` 不进入 executable target type；deployment 只作为 rollback plan source。

### 5.2 新增表

新增表名固定为：

- `operator_recovery_actions`

字段固定如下：

| 字段 | 类型方向 | 约束 | 说明 |
| --- | --- | --- | --- |
| `id` | uuid | pk | action id |
| `candidate_id` | varchar(255) | nullable | 来源 candidate id |
| `action_type` | enum | not null | 见 `operator_recovery_action_type` |
| `target_type` | enum | not null | 见 `operator_recovery_target_type` |
| `target_refs` | jsonb | not null | 目标对象引用 |
| `status` | enum | not null default `queued` | action lifecycle |
| `actor` | varchar(255) | not null | operator label；无用户模型前使用 service/operator 字符串 |
| `reason` | text | not null | 人工确认原因 |
| `idempotency_key` | varchar(255) | not null unique | 防重复执行 |
| `preview_id` | varchar(255) | not null | preview 响应返回的不透明 id |
| `source_facts` | jsonb | not null | candidate source facts snapshot |
| `preview` | jsonb | not null | preview/dry-run 结果 |
| `before_facts` | jsonb | not null | 执行前事实快照 |
| `after_facts` | jsonb | nullable | 执行后事实快照 |
| `queue_job_refs` | jsonb | nullable | 下游 queue/job refs |
| `diagnosis_code` | varchar(64) | nullable | 失败分类 |
| `error_message` | text | nullable | 失败详情 |
| `created_at` | timestamptz | not null default now | 创建时间 |
| `started_at` | timestamptz | nullable | 开始执行时间 |
| `completed_at` | timestamptz | nullable | 终态时间 |
| `updated_at` | timestamptz | not null default now | 更新时间 |

### 5.3 索引

新增索引固定为：

| 索引 | 目的 |
| --- | --- |
| primary key `id` | action lookup |
| unique `idempotency_key` | 防重复创建 |
| `(status, updated_at)` | active action reconciliation |
| `(action_type, created_at)` | ops 列表与审计筛选 |

不在 P0 添加：

- `recovery_audit_entries`
- rollback table
- candidate table
- wide JSONB GIN index

---

## 6. Shared Types Freeze

### 6.1 新增枚举类型

`packages/shared-types` 必须新增并导出：

```ts
export type OpsRecoveryCandidateSourceType =
  | "diagnostic_sample"
  | "answer_session_replay"
  | "document_replay"
  | "deployment_compare";

export type OpsRecoveryActionType =
  | "document_reindex"
  | "document_retry"
  | "answer_diagnostic_rerun";

export type OpsRecoveryTargetType = "document" | "answer_session";

export type OpsRecoveryRiskLevel = "low" | "medium" | "high";

export type OpsRecoveryRecommendationState =
  | "recommended"
  | "available"
  | "blocked"
  | "not_applicable";

export type OpsRecoveryActionStatus =
  | "queued"
  | "running"
  | "succeeded"
  | "failed"
  | "cancelled"
  | "blocked";

export type OpsRollbackPlanConfidence = "low" | "medium" | "high";
```

冻结结论：

1. `OpsRecoveryActionStatus` 与 DB enum 一一对应。
2. `OpsRecoveryRiskLevel` 不复用 `IncidentSeverity`，避免把 incident 严重度和执行风险混成一个 contract。
3. `blocked` 是 recommendation/action status，不是 risk level。

### 6.2 新增基础 DTO

字段命名固定使用现有 API 风格的 `snake_case`。

```ts
export interface OpsRecoveryTargetRef {
  type: OpsRecoveryTargetType;
  id: string;
}

export interface OpsRecoveryFactSnapshot {
  captured_at: string;
  target_type: OpsRecoveryTargetType;
  target_refs: OpsRecoveryTargetRef[];
  facts: Record<string, unknown>;
}

export interface OpsRecoveryPrecondition {
  code: string;
  label: string;
  satisfied: boolean;
  detail: string | null;
}

export interface OpsRecoveryCandidate {
  candidate_id: string;
  source_type: OpsRecoveryCandidateSourceType;
  source_ref: string;
  action_type: OpsRecoveryActionType;
  target_type: OpsRecoveryTargetType;
  target_refs: OpsRecoveryTargetRef[];
  risk_level: OpsRecoveryRiskLevel;
  recommendation_state: OpsRecoveryRecommendationState;
  title: string;
  summary: string;
  preconditions: OpsRecoveryPrecondition[];
  blocked_reason: string | null;
  preview_ref: OpsReplayRef;
}
```

### 6.3 新增请求 / 响应 DTO

必须新增并导出：

- `OpsRecoveryCandidateListQuery`
- `OpsRecoveryCandidateListResponse`
- `OpsRecoveryActionPreviewRequest`
- `OpsRecoveryActionPreviewResponse`
- `OpsRecoveryActionCreateRequest`
- `OpsRecoveryActionResponse`
- `OpsRecoveryActionAuditResponse`
- `OpsRollbackPlanQuery`
- `OpsRollbackPlanResponse`

冻结字段如下：

```ts
export interface OpsRecoveryCandidateListQuery {
  source_type?: OpsRecoveryCandidateSourceType;
  source_ref?: string;
  action_type?: OpsRecoveryActionType;
  risk_level?: OpsRecoveryRiskLevel;
  recommendation_state?: OpsRecoveryRecommendationState;
  page?: number;
  page_size?: number;
}

export interface OpsRecoveryCandidateListResponse {
  generated_at: string;
  page: number;
  page_size: number;
  total: number;
  items: OpsRecoveryCandidate[];
}

export interface OpsRecoveryActionPreviewRequest {
  candidate_id: string;
  action_type: OpsRecoveryActionType;
  target_type: OpsRecoveryTargetType;
  target_refs: OpsRecoveryTargetRef[];
}

export interface OpsRecoveryActionPreviewResponse {
  preview_id: string;
  generated_at: string;
  expires_at: string;
  candidate_id: string;
  action_type: OpsRecoveryActionType;
  target_type: OpsRecoveryTargetType;
  target_refs: OpsRecoveryTargetRef[];
  risk_level: OpsRecoveryRiskLevel;
  recommendation_state: OpsRecoveryRecommendationState;
  preconditions: OpsRecoveryPrecondition[];
  blocked_reason: string | null;
  estimated_blast_radius: string;
  idempotency_key: string;
  source_facts: OpsRecoveryFactSnapshot;
  before_facts: OpsRecoveryFactSnapshot;
}

export interface OpsRecoveryActionCreateRequest {
  candidate_id: string;
  preview_id: string;
  idempotency_key: string;
  reason: string;
}

export interface OpsRecoveryActionResponse {
  action_id: string;
  candidate_id: string | null;
  status: OpsRecoveryActionStatus;
  action_type: OpsRecoveryActionType;
  target_type: OpsRecoveryTargetType;
  target_refs: OpsRecoveryTargetRef[];
  queue_job_refs: OpsReplayRef[];
  diagnosis_code: DiagnosisCode | null;
  error_message: string | null;
  created_at: string;
  started_at: string | null;
  completed_at: string | null;
  updated_at: string;
}
```

Audit 和 rollback plan 字段：

```ts
export interface OpsRecoveryStatusTimelineEntry {
  status: OpsRecoveryActionStatus;
  at: string;
  summary: string;
}

export interface OpsRecoveryActionAuditResponse {
  generated_at: string;
  action: OpsRecoveryActionResponse;
  actor: string;
  reason: string;
  source_facts: OpsRecoveryFactSnapshot;
  preview: OpsRecoveryActionPreviewResponse;
  before_facts: OpsRecoveryFactSnapshot;
  after_facts: OpsRecoveryFactSnapshot | null;
  status_timeline: OpsRecoveryStatusTimelineEntry[];
  manual_follow_up: string[];
}

export interface OpsRollbackPlanQuery {
  deployment_record_id: string;
  window?: OpsTrendWindow;
}

export interface OpsRollbackPlanResponse {
  generated_at: string;
  deployment_record_id: string;
  compare_ref: OpsReplayRef;
  affected_samples: OpsDiagnosticSample[];
  quality_delta_summary: OpsDeploymentCompareDeltaSummary;
  smoke_summary: string;
  confidence: OpsRollbackPlanConfidence;
  missing_evidence: string[];
  manual_checklist: string[];
}
```

---

## 7. API Contract Freeze

### 7.1 保持稳定的既有路径

以下路径保持不重命名、不改语义：

- `GET /api/v1/ops/samples`
- `GET /api/v1/ops/replays/answer-sessions/:sessionId`
- `GET /api/v1/ops/replays/documents/:documentId`
- `GET /api/v1/ops/deployments/compare`
- `POST /api/v1/documents/:documentId/retry`
- `POST /api/v1/documents/:documentId/reindex`
- `GET /api/v1/jobs/:jobId`

### 7.2 新增路径

新增路径固定如下：

| Method | Path | 语义 |
| --- | --- | --- |
| `GET` | `/api/v1/ops/recovery/candidates` | 返回 recovery candidates |
| `POST` | `/api/v1/ops/recovery/actions/preview` | 生成 dry-run preview，不创建 action |
| `POST` | `/api/v1/ops/recovery/actions` | 人工确认后创建并执行 action |
| `GET` | `/api/v1/ops/recovery/actions/:actionId` | 查询 action status |
| `GET` | `/api/v1/ops/recovery/actions/:actionId/audit` | 查询 action audit |
| `GET` | `/api/v1/ops/recovery/rollback-plan` | 返回 manual rollback checklist |

冻结结论：

1. `/ops/recovery/actions/preview` 不写 `operator_recovery_actions`。
2. `/ops/recovery/actions` 必须写 `operator_recovery_actions`，并立即返回 action status。
3. `/ops/recovery/rollback-plan` 是 read-only API，不执行 deploy/rollback。
4. 不新增 `POST /api/v1/answers/:sessionId/rerun`。

### 7.3 API Client / OpenAPI 义务

实现阶段只要新增上述 DTO 或 path，必须同步更新：

- `packages/shared-types/src/index.ts`
- `apps/api/src/ops/ops.dto.ts`
- `apps/api/src/ops/ops.controller.ts`
- generated OpenAPI
- `packages/api-client/src/index.ts`
- Web adapter / tests

---

## 8. Recovery Action State Machine Freeze

### 8.1 状态集合

Active statuses：

- `queued`
- `running`

Terminal statuses：

- `succeeded`
- `failed`
- `cancelled`
- `blocked`

### 8.2 状态转换

允许转换：

```text
queued -> running
queued -> blocked
queued -> cancelled
queued -> failed
running -> succeeded
running -> failed
running -> cancelled
running -> blocked
```

不允许转换：

- terminal -> active
- `succeeded` -> `failed`
- `failed` -> `succeeded`
- preview -> action status

### 8.3 Liveness / reconciliation

服务端必须负责把不可恢复 active action 收口到 terminal status。

冻结规则：

1. `queued` 超过实现冻结中确定的阈值且找不到可执行下游 job，收口为 `failed` 或 `blocked`。
2. `running` 的下游 queue job failed、dead、stalled/exhausted retries 时，收口为 `failed`。
3. 下游 job succeeded 后，action 必须刷新 `after_facts` 并收口为 `succeeded`，若 after facts 不满足成功条件则收口为 `failed`。
4. 前端轮询只以 action terminal status 停止；前端 timeout fallback 只能停止本地轮询并提示刷新，不能伪造服务端终态。
5. Reconciliation owner 是 API/service 层；worker 可以报告下游 job 结果，但不能成为唯一收口机制。

---

## 9. Action Execution Freeze

### 9.1 `document_reindex`

目标：

- `target_type = document`
- `target_refs` 只能包含 document id

执行方向：

- 复用 document indexing queue / service capability
- action envelope 记录 operator reason、preview、before/after facts 和 queue refs

成功条件：

- 下游 indexing job succeeded
- document `index_status` 收口到 `ready`
- `citation_ready = true`

### 9.2 `document_retry`

目标：

- `target_type = document`
- `target_refs` 只能包含 document id

执行方向：

- 复用 document processing queue / service capability
- action envelope 记录 operator reason、preview、before/after facts 和 queue refs

成功条件：

- 下游 processing job succeeded
- document parse/index path 不再处于 failed terminal

### 9.3 `answer_diagnostic_rerun`

目标：

- `target_type = answer_session`
- `target_refs` 只能包含 answer session id

执行方向：

- P0 只允许 read-only diagnostic replay refresh / evaluation preview
- 不写 `answer_sessions`
- 不写 `answer_claims`
- 不写 `answer_citations`
- 不写 retrieval facts

冻结结论：

1. `answer_diagnostic_rerun` 不替换用户可见答案。
2. 任何重跑 answer orchestration、retrieval ranking、citation mapping 或 refusal logic 的需求必须先新增 `answer-quality-gate` 决策记录，并更新 eval assumptions。

---

## 10. Answer Quality Gate Freeze

`Phase 3B / P0` 默认不改变用户可见 answer contract。

必须保持：

1. no-evidence refusal 行为不变
2. citation mapping 不变
3. retrieval scope 不变
4. freshness badge / freshness flag 语义不变
5. answer session terminal facts 不被 recovery action 改写

允许：

- 在 audit / preview 中展示“如果重新诊断，可能需要人工检查”的证据
- 生成 read-only diagnostic output
- 指向既有 answer replay / retrieval trace

不允许：

- 用 recovery action 自动生成新答案并覆盖旧答案
- 用 recovery action 静默改变 citation/refusal/freshness 结论
- 在没有 eval contract 的情况下改变 retrieval ranking

---

## 11. Rollback Plan Freeze

`GET /api/v1/ops/recovery/rollback-plan` 固定为 read-only manual checklist。

必须包含：

- deployment compare reference
- affected samples
- quality delta summary
- smoke summary
- confidence
- missing evidence
- manual checklist

不得包含：

- rollback execution endpoint
- deploy credential
- automatic production rollback
- implicit approval state

---

## 12. Production `not_indexed` Boundary

历史 production `not_indexed` 治理仍是版本外运维动作。

冻结结论：

1. 不把 production `not_indexed` backfill 默认并入 P0 product action。
2. 若需要执行，先单独运行 `pnpm recovery:backfill-indexing -- --dry-run`。
3. Dry-run 结果可以作为 recovery candidate source facts，但不能绕过 preview/action/audit。

---

## 13. Implementation Gate

进入 implementation freeze 前必须确认：

1. 本文新增 schema、DTO、path 和状态机没有未决命名。
2. Implementation lanes 不得自行新增或改名 action type、target type、status、path、DTO 字段。
3. OpenAPI/API client 由 contract-to-code lane 一次性更新。
4. Web/worker/test lanes 只能依赖冻结后的 shared-types 和 API client。
5. 如果实现发现 contract 无法落地，回到 contract freeze 文档修订，不在代码里临时改语义。

---

## 14. Freeze-Ready 结论

1. `Phase 3B / P0` 可以进入 implementation freeze。
2. 首个 implementation lane 必须是 contract-to-code：schema、shared-types、DTO、OpenAPI、API client。
3. 后续 lanes 才能推进 API service、worker/action execution、Web `/ops` recovery workflow 和测试。
4. `v7 / Phase 3A` diagnostic read model 继续作为上游事实源，不反向扩 scope。
