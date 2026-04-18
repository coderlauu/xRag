# Phase 3B Data Model

**日期：** 2026-04-18
**版本：** `v8 / Phase 3B`
**状态：** draft
**对应文档：**
- [Phase 3B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-prd.md)
- [Phase 3B Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-backlog.md)
- [Phase 3B Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-18-phase-3b-architecture.md)
- [Phase 3B P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-18-phase-3b-p0-technical-tradeoffs.md)
- [Phase 3A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-17-phase-3a-data-model.md)

---

## 1. 本文档目的

这份文档用于回答 `Phase 3B / P0` 的数据面问题：

- recovery candidate、operator action、audit、rollback plan 各自沿用哪些现有表
- 本轮是否需要新增 durable table
- action status、action type、target、idempotency、before/after facts 如何表达
- 哪些能力只是 read model 派生，不应提前落成 schema

本阶段不写 migration 细节，但要先冻结数据面方向。

---

## 2. 当前可直接复用的事实源

### 2.1 Recovery candidate

| 能力 | 当前事实源 | 当前情况 |
| --- | --- | --- |
| answer issue candidate | `answer_sessions`, `retrieval_runs`, `answer_citations`, v7 answer replay | 已能识别 failed/stuck/no evidence/citation/freshness 相关诊断事实 |
| document issue candidate | `documents`, `document_parse_jobs`, `document_processing_events`, v7 document replay | 已能识别 parse/index/backlog/stale/failure |
| release issue candidate | `deployment_records`, `evaluation_runs`, v7 deployment compare | 已能围绕 deployment anchor 输出 affected samples |

### 2.2 Existing execution facts

| 能力 | 当前事实源 | 当前情况 |
| --- | --- | --- |
| document retry | `document_parse_jobs`, `documents`, `document_processing_events` | 已有 direct API 与 queue job，但缺少 recovery action envelope |
| document reindex | `document_parse_jobs`, `documents`, `document_processing_events`, `document_chunks` | 已有 direct API 与 queue job，但缺少 preview/action/audit |
| answer active reconciliation | `answer_sessions.queue_job_id`, `answer_sessions.status`, worker exhausted failure handler | 已有 answer session liveness guardrail，可作为 action liveness 模式参考 |

---

## 3. 数据面结论

### 3.1 `recovery_candidate` 默认不建表

**结论**

- `recovery_candidate` 是 read model。
- Candidate id 可以由 source facts deterministic 派生。

**推荐 id 形状**

- `candidate_id`
  - `document_reindex:{document_id}:{source_ref}`
  - `document_retry:{document_id}:{source_ref}`
  - `answer_diagnostic:{session_id}:{source_ref}`
  - `rollback_plan:{deployment_record_id}:{window}`

**原因**

- Candidate 是诊断事实的下一步解释，不是执行事实。
- Candidate 会随着 underlying facts 变化，持久化反而容易过期。

### 3.2 `operator_recovery_actions` 需要 durable table

**结论**

`Phase 3B / P0` 需要新增 durable operator action fact，用于记录人工确认后的执行事实。

**推荐表名**

- `operator_recovery_actions`

**推荐字段**

| 字段 | 类型方向 | 说明 |
| --- | --- | --- |
| `id` | uuid pk | action id |
| `candidate_id` | varchar nullable | 来源 candidate id；rollback plan 等只读能力不一定会执行 |
| `action_type` | enum/text | `document_reindex`, `document_retry`, `answer_diagnostic_rerun` |
| `status` | enum/text | `queued`, `running`, `succeeded`, `failed`, `cancelled`, `blocked` |
| `actor` | varchar/text | 当前没有正式用户模型，先以 operator label/service actor 表达 |
| `reason` | text | 人工确认原因 |
| `target_type` | enum/text | `document`, `answer_session`, `deployment` |
| `target_refs` | jsonb | 目标对象 id 列表或结构 |
| `idempotency_key` | varchar unique | 防重复执行 |
| `source_facts` | jsonb | candidate source facts 摘要 |
| `preview` | jsonb | preview/dry-run 结果 |
| `before_facts` | jsonb | 执行前事实快照 |
| `after_facts` | jsonb nullable | 执行后事实快照 |
| `queue_job_refs` | jsonb nullable | 下游 BullMQ/document job refs |
| `diagnosis_code` | varchar nullable | 失败分类 |
| `error_message` | text nullable | 失败详情 |
| `created_at` | timestamptz | 创建时间 |
| `started_at` | timestamptz nullable | 开始时间 |
| `completed_at` | timestamptz nullable | 完成时间 |
| `updated_at` | timestamptz | 更新时间 |

**原因**

- `document_parse_jobs` 只能表达 document job，不足以表达 operator reason、candidate、preview、before/after facts 和 answer/rollback targets。
- 直接把审计塞进 job payload 会把生产操作事实和 worker job facts 混在一起。

### 3.3 action status 不复用 `job_status`

**结论**

- `operator_recovery_action.status` 应是独立应用层状态。
- 不复用 `job_status`，也不改写 `job_status` enum。

**推荐状态**

- `queued`
- `running`
- `succeeded`
- `failed`
- `cancelled`
- `blocked`

**不推荐状态**

- `previewed`
  - preview 如果不创建 durable action，就不应进入 action status
- `stuck`
  - stuck 是失败诊断，不是 terminal status
- `timed_out`
  - 可通过 `failed + diagnosis_code` 表达，避免扩散状态枚举

### 3.4 action type 与 target type

**推荐 `action_type`**

- `document_reindex`
- `document_retry`
- `answer_diagnostic_rerun`

**明确不进入 P0 的 action_type**

- `auto_remediate`
- `production_rollback`
- `full_corpus_reindex`
- `model_route_change`

**推荐 `target_type`**

- `document`
- `answer_session`

`deployment` 在 P0 中只用于 rollback plan source，不进入 executable target。

### 3.5 audit 是 action table 的查询视图，不单独建表

**结论**

- `recovery_audit_entry` 首版可以由 `operator_recovery_actions` 直接查询生成。
- 不单独新增 `recovery_audit_entries` 表。

**原因**

- P0 的 audit 是每个 action 的事实回看，不是独立事件流。
- 单表可以减少 migration 和 consistency 风险。

**允许后续扩展**

- 如果 implementation freeze 发现需要细粒度 status transition event，可在后续版本新增 append-only action event table。

### 3.6 rollback plan 不建表

**结论**

- `rollback_plan` 是 deployment compare 上方的 read model。
- 不新增 rollback table，不新增 rollback action type。

**原因**

- P0 不执行 rollback。
- 持久化 rollback plan 容易被误解为系统已批准或已执行回滚。

---

## 4. 需要重点盯住的查询与索引

1. `operator_recovery_actions(id)`
   - primary key
2. `operator_recovery_actions(idempotency_key)`
   - unique index
3. `operator_recovery_actions(status, updated_at)`
   - reconciliation 扫描 active actions
4. `operator_recovery_actions(action_type, created_at)`
   - ops 列表和审计筛选
5. `operator_recovery_actions(target_type)`
   - 可选；如果 target_refs 是 jsonb，首版不建议复杂查询目标 id

结论：

- 如果 P0 只通过 action id 和最近 actions 查询，索引集合可以保持很小。
- 不应为了 batch analytics 提前添加宽索引或新表。

---

## 5. Freeze-Ready 结论

1. Candidate 与 rollback plan 是 read model，不建表。
2. Operator recovery action 需要 durable table。
3. Audit 首版由 action table 生成，不单独建表。
4. 不修改既有 `job_status`、`answer_session_status`、`index_status` enum。
5. Action status/type/target 是新的 contract surface，contract freeze 必须锁定。
6. Answer diagnostic rerun 默认不能改写 answer facts；如需改写，必须重新走 answer-quality gate。
