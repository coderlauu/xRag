# Phase 3B Architecture

**日期：** 2026-04-18
**版本：** `v8 / Phase 3B`
**状态：** draft
**对应文档：**
- [Phase 3B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-prd.md)
- [Phase 3B Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-18-xrag-phase-3b-backlog.md)
- [v8 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-18-v8-interaction-delta.md)
- [Phase 3B P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-18-phase-3b-p0-technical-tradeoffs.md)
- [Phase 3A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md)
- [Ask Active Session Stuck Polling Retrospective](/Users/coderlauu/xRag/docs/retro/2026-04-17-ask-active-session-stuck-polling-retrospective.md)

---

## 1. 本文档目的

这份文档用于回答 `Phase 3B` 进入 contract freeze 前最关键的架构问题：

- recovery candidate、operator action、execution audit 和 rollback plan 应该如何分层
- 哪些能力继续是 read model，哪些能力是 mutation
- 现有 document retry/reindex API 能否直接作为 Phase 3B recovery action
- 新的 recovery action 如何避免重演异步 stuck polling 问题
- answer diagnostic rerun 是否触碰 answer-quality gate

本阶段不冻结最终 DTO 细节，但要冻结架构方向和边界。

---

## 2. 当前工程事实

1. `Phase 3A` 已在 `ops` 资源族下落地只读诊断 read models：
   - `GET /api/v1/ops/samples`
   - `GET /api/v1/ops/replays/answer-sessions/:sessionId`
   - `GET /api/v1/ops/replays/documents/:documentId`
   - `GET /api/v1/ops/deployments/compare`
2. `packages/shared-types` 已导出 `OpsDiagnosticSample*`、`OpsAnswerSessionReplayResponse`、`OpsDocumentReplayResponse`、`OpsDeploymentCompareResponse` 等 v7 contract。
3. `packages/api-client` 已有对应 fetch helper，Web `/ops` 已使用 diagnostic workflow。
4. Documents 侧已有 direct mutation：
   - `POST /api/v1/documents/:documentId/retry`
   - `POST /api/v1/documents/:documentId/reindex`
5. Document retry/reindex 当前会创建 `document_parse_jobs`，并更新 `documents` projection 与 `document_processing_events`。
6. Document indexing worker 会写 `document_parse_jobs`、`documents.index_status`、`document_processing_events`，但当前没有独立 recovery action id 或 action audit。
7. Answer orchestration 已有服务端 stale active reconciliation：
   - API 读侧会将超时 active answer session 收口到 `failed`
   - Worker exhausted failure 会尽量按 queue job id 对账 answer session
8. Queue 层当前有 document-processing、document-indexing、answer-orchestration 三类队列，没有 recovery-specific queue。
9. 当前 schema 没有 operator action 或 recovery audit table。

结论：`Phase 3B` 不是从零做运维执行平台，而是在 v7 read model 和既有 document/answer/queue 能力上增加受控 recovery action 层。

---

## 3. 架构问题与结论

### 3.1 candidate、action、audit、plan 的分层

**结论**

- `recovery_candidate` 是 read model。
- `operator_recovery_action` 是 durable mutation fact。
- `recovery_audit_entry` 是 action 的查询视图，不是前端日志。
- `rollback_plan` 是 read model/manual checklist，不是 mutation。

**原因**

- Candidate 来自 diagnostic facts，频繁随 facts 变化；过早持久化会带来 freshness 和去重复杂度。
- Action 是生产操作事实，必须有 id、actor、reason、target、status 和审计。
- Audit 是 action fact 的展示方式，必须能追溯 before/after facts。
- Rollback 在 v8 只做人工计划，不能混入 automatic execution。

### 3.2 是否复用现有 document retry/reindex API

**结论**

- 现有 `POST /api/v1/documents/:documentId/retry` 与 `POST /api/v1/documents/:documentId/reindex` 不能直接作为 v8 P0 的 operator action API。
- 它们可以作为 implementation 内部执行能力被 recovery action service 调用或复用其核心逻辑。

**原因**

- 现有 API 没有 preview/dry-run。
- 现有 API 没有 action id、actor、reason、idempotency key、before/after facts。
- 现有 API 返回 job id，但不能表达 recovery action lifecycle。
- 直接把这些 endpoint 暴露为 recovery UI 按钮会绕过 Phase 3B 的审计边界。

**实施约束**

- Phase 3B 应新增 `ops/recovery` 资源族来承载 preview、execution 和 audit。
- 旧 document retry/reindex API 可保留兼容，但 `/ops` recovery UI 不应直接调用它们作为 P0 主路径。

### 3.3 recovery 是否需要新 queue

**结论**

- 首版可以不新增独立 recovery queue。
- `operator_recovery_action` 可以作为 durable envelope，内部根据 action type 复用 document-indexing、document-processing 或 answer diagnostic read path。
- 只有当 action 本身需要跨多个 job 编排时，才在 contract freeze 评估新增 recovery queue。

**原因**

- Document reindex 已能通过 document-indexing queue 执行。
- Document retry 已能通过 document-processing queue 执行。
- Answer diagnostic rerun 如果限定为 read-only re-evaluation/replay refresh，可不进入 answer-orchestration queue。
- 新增 recovery queue 会扩大 worker、retry、deployment 和 reconciliation 面。

**实施约束**

- 即便不新增 recovery queue，action status 也必须与下游 job status 对账。
- 下游 job failed/stalled/exhausted retries 必须能把 action 收口到 terminal failed。

### 3.4 action liveness guardrail

**结论**

- Recovery action 必须继承 Ask stuck polling 的 liveness 规则。
- 不可恢复 active action 必须由服务端收口到 terminal failed。
- 前端 stuck fallback 只能停止无意义轮询，不能伪造服务端 terminal status。

**推荐状态层次**

- active:
  - `previewed`
  - `queued`
  - `running`
- terminal:
  - `succeeded`
  - `failed`
  - `cancelled`
  - `blocked`

**注意**

- `previewed` 是否应算 active 需在 contract freeze 确认。若 preview 不创建 durable action，则它不应进入 action status。

### 3.5 answer diagnostic rerun 的 answer-quality 边界

**结论**

- Phase 3B 不应把 answer diagnostic rerun 做成“重新生成并替换用户可见答案”。
- 若需要重新运行 answer orchestration、retrieval ranking 或 citation mapping，必须进入 `answer-quality-gate`。
- P0 默认只允许 answer diagnostic replay refresh 或 read-only evaluation preview，不改写 `answer_sessions` 的 terminal answer facts。

**原因**

- 重新生成答案会触碰 retrieval、citation、freshness、refusal 和 eval contract。
- v8 的主目标是 ops recovery，不是重新打开 Phase 2B answer behavior。

### 3.6 rollback plan 的架构位置

**结论**

- Rollback plan 继续挂在 `ops` 资源族下，基于 deployment compare 派生。
- Rollback plan 不创建 deployment mutation，不调用 deploy API，不执行生产回滚。

**原因**

- 当前没有权限、审批、环境编排、回滚执行或回滚验证 contract。
- 自动 rollback 应作为后续独立版本处理。

---

## 4. 推荐总体架构

```text
Browser
  -> Web SPA
      -> Ops diagnostic workflow
          -> recovery candidates
          -> preview / confirmation
          -> action status
          -> action audit
          -> rollback plan
  -> API
      -> existing ops diagnostic read models
          -> /ops/samples
          -> /ops/replays/answer-sessions/:sessionId
          -> /ops/replays/documents/:documentId
          -> /ops/deployments/compare
      -> new ops recovery resources
          -> /ops/recovery/candidates
          -> /ops/recovery/actions/preview
          -> /ops/recovery/actions
          -> /ops/recovery/actions/:actionId
          -> /ops/recovery/actions/:actionId/audit
          -> /ops/recovery/rollback-plan
      -> existing execution primitives
          -> document retry/reindex service logic
          -> document-processing queue
          -> document-indexing queue
          -> answer replay/read-only diagnostic path
  -> PostgreSQL
      -> existing diagnostic facts
      -> new operator recovery action facts
      -> before/after fact snapshots
```

---

## 5. Freeze-Ready 结论

1. `Phase 3B` 应被视为“diagnostic-to-recovery workflow 版本”，不是自动 remediation 平台。
2. Candidate 和 rollback plan 默认是 read model。
3. Operator action 和 audit 需要 durable facts，不能只复用 document job row。
4. 现有 document retry/reindex API 不能直接作为 `/ops` recovery 主路径；应包进 action envelope。
5. Answer diagnostic rerun 默认只读；任何会重跑 answer generation 或改写 answer facts 的能力必须先进入 `answer-quality-gate`。
6. 下一步可以进入 contract freeze，重点锁定 action schema、status enum、API path、DTO 和 liveness semantics。
