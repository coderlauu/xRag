# Phase 3A Contract Freeze Prerequisites

**日期：** 2026-04-17
**版本：** `v7 / Phase 3A`
**状态：** draft
**对应文档：**
- [Phase 3A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-architecture.md)
- [Phase 3A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-17-phase-3a-data-model.md)
- [Phase 3A API Design](/Users/coderlauu/xRag/tech/api/2026-04-17-phase-3a-api.md)

---

## 1. 目的

这份文档只回答一个问题：

- `Phase 3A` 现在是否可以进入 contract freeze

结论先写：

- 可以进入
- 当前没有新的硬 blocker
- 但 contract freeze 必须把下面这些事项一次性锁死

---

## 2. 已完成的前提

以下方向已经在技术评估中收敛：

1. `Phase 3A` 继续沿用 `answers / documents / ops / evaluation_runs / deployment_records` 的正式事实链，不新造影子 explain pipeline。
2. `diagnostic_sample` 已收敛为 read model，而不是新表。
3. `deployment compare` 已收敛为以 `deployment_records.id` 为 anchor 的 compare 视图。
4. 新增能力全部挂在 `ops` 资源族下，不改写 `Ask / Search / Detail` 主状态机。
5. `Phase 3A / P0` 默认不新增 durable core table，schema 变化只允许在后续如有必要时做 additive 优化。

---

## 3. Contract Freeze 必须锁定的事项

### 3.1 资源与路径

需要正式冻结：

- `GET /api/v1/ops/samples`
- `GET /api/v1/ops/replays/answer-sessions/:sessionId`
- `GET /api/v1/ops/replays/documents/:documentId`
- `GET /api/v1/ops/deployments/compare`

同时明确既有路径继续保留为事实面：

- `GET /api/v1/answers/:sessionId`
- `GET /api/v1/answers/:sessionId/retrieval`
- `GET /api/v1/documents/:documentId`
- `GET /api/v1/documents/:documentId/evidence`
- `GET /api/v1/documents/:documentId/timeline`

### 3.2 新 shared types

至少需要冻结：

- `OpsDiagnosticSample`
- `OpsDiagnosticSampleListResponse`
- `OpsDiagnosticSampleKind`
- `OpsDiagnosticOrigin`
- `OpsAnswerSessionReplayResponse`
- `OpsDocumentReplayResponse`
- `OpsDeploymentCompareResponse`

并明确哪些字段直接复用：

- `AnswerSessionResponse`
- `AnswerRetrievalTraceResponse`
- `DocumentDetail`
- `DocumentTimelineResponse`
- `DocumentEvidenceResponse`

### 3.3 样本主键与分类

contract freeze 时必须写死：

- `sample_kind`
  - `answer_session`
  - `document_pipeline`
- `sample_id` 格式
  - `answer_session:{session_id}`
  - `document:{document_id}`
- `origin`
  - `trend`
  - `incident_cluster`
  - `release_compare`

注意：

- `deployment_compare_window` 不是新的 sample kind。
- 不在 freeze 阶段引入第三种通用 observability sample。

### 3.4 query 与窗口语义

至少需要冻结：

- `window`
  - `24h`
  - `7d`
  - `30d`
- `deployment_record_id` 作为 compare anchor 的必填规则
- `samples` 的筛选 query：
  - `origin`
  - `sample_kind`
  - `cluster_key`
  - `deployment_record_id`
  - `page`
  - `page_size`

### 3.5 “新回归” 的 contract 边界

必须明确：

1. “新回归” 是 compare read model 的结论字段，不进入主 schema 状态枚举。
2. before/after 的对比规则必须固定，不允许 Web 自己定义。
3. deployment compare 输出的是样本列表和 delta summary，不是自动回滚建议。

### 3.6 终端页面暴露边界

如果 `Ask / Search / Detail` 要暴露轻量诊断入口，freeze 时必须明确：

- 只返回轻量 notice 或跳转信息
- 不返回 replay payload
- 不改写现有 answer status 语义
- 不把 `ops` 内部对象直接扩散到终端主流程

---

## 4. 当前仍需在 Freeze 中写清的点

这些不是 blocker，但必须在 freeze 文档里落成正式决定：

1. `samples` 是否采用 page/page_size 还是 cursor
2. replay wrapper 中 `related_context` 的最小字段集
3. document replay 是否在 `P0` 直接暴露 `related_answer_session_count`
4. deployment compare 的 `delta_summary` 最小指标集
5. 如果后续实现确实需要 additive index，哪些索引属于 `P0` 必需项

---

## 5. 推荐结论

建议下一步直接进入 `contract-freeze`，并按以下顺序推进：

1. 锁定 `samples / replay / compare` 的资源路径与 query 形状
2. 锁定 `sample_kind / sample_id / origin` 的对象语义
3. 锁定 wrapper DTO 与复用 DTO 的边界
4. 最后再决定哪些轻量 notice 可以暴露到 `Ask / Search / Detail`

在这个顺序下，`Phase 3A` 可以继续保持“内部诊断增强”而不是膨胀成新平台。
