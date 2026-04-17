# xRag Phase 3A Contract Freeze

**日期：** 2026-04-17
**版本：** `v7 / Phase 3A`
**状态：** freeze-ready
**适用范围：** `P0-01 ~ P0-04`
**对应文档：**
- [Phase 3A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-architecture.md)
- [Phase 3A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-17-phase-3a-data-model.md)
- [Phase 3A API Design](/Users/coderlauu/xRag/tech/api/2026-04-17-phase-3a-api.md)
- [Phase 3A Contract Freeze Prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-17-phase-3a-contract-freeze-prerequisites.md)
- [Phase 3A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-prd.md)
- [Phase 3A Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-backlog.md)
- [Phase 2C Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-contract-freeze.md)

---

## 1. 文档目的

这份文档把 `Phase 3A / P0` 进入实现前最需要稳定的五类 contract 固定下来：

1. `schema`
2. `shared-types`
3. `API contract`
4. `ops diagnostic read model`
5. `diagnostic sample / replay / deployment compare` 语义

后续编码若与本文冲突，以本文为准；如需偏离，必须先回写文档，而不是在实现中临时改语义。

---

## 2. Source Of Truth 顺序

`Phase 3A / P0` 的 contract source-of-truth 顺序固定为：

1. [Phase 3A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-contract-freeze.md)
2. [Phase 3A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-17-phase-3a-data-model.md)
3. [Phase 3A API Design](/Users/coderlauu/xRag/tech/api/2026-04-17-phase-3a-api.md)
4. [Phase 2C Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-contract-freeze.md)
5. `packages/shared-types/src/index.ts`
6. `apps/api/src/ops/ops.dto.ts`
7. `apps/api/src/ops/ops.controller.ts`
8. OpenAPI / SDK / 实现代码

---

## 3. Schema Freeze

### 3.1 保持稳定的既有 enum / 状态机

以下既有 enum 名称和值在 `Phase 3A / P0` 保持稳定，不重命名、不改语义：

| enum / type | 值 |
| --- | --- |
| `AnswerSessionStatus` | `idle`, `retrieving`, `synthesizing`, `answered`, `needs_scope`, `refused`, `failed` |
| `IndexStatus` | `not_indexed`, `queued`, `chunking`, `embedding`, `ready`, `failed`, `stale` |
| `DeploymentSmokeStatus` | `passed`, `failed`, `unknown` |
| `EvaluationRunStatus` | `running`, `completed`, `failed` |
| `OpsTrendWindow` | `24h`, `7d`, `30d` |
| `OpsReadinessBlockingReason` | `none`, `no_ready_documents`, `indexing_backlog`, `indexing_failed`, `stale_corpus` |
| `OpsReleaseGuardRiskLevel` | `healthy`, `warning`, `critical` |

冻结结论：

1. 不新增 `answer_session_status` 值。
2. 不新增 `index_status` 值。
3. 不把 `diagnostic_sample` 写成新的 DB enum。
4. 不把 compare 结果写入主业务表状态字段。

### 3.2 不新增 durable core table

`Phase 3A / P0` 不新增：

- `diagnostic_samples`
- `answer_session_replays`
- `document_pipeline_replays`
- `deployment_compare_windows`
- `ops_replay_snapshots`
- `ops_regressions`
- `ops_logs`

冻结结论：

1. `diagnostic_sample` 是 API read model，不是持久表。
2. `answer replay / document replay / deployment compare` 都是只读聚合资源，不写新事实。
3. “新回归” 是 compare response 的分类，不是新 schema 状态。

### 3.3 允许的后续 schema 变化边界

若 implementation freeze 评估真实查询后确认性能不足，只允许讨论 additive index，例如：

- `answer_sessions(finished_at)`
- `answer_sessions(updated_at)`
- `document_processing_events(document_id, created_at)` 当前已存在，可优先复用
- `deployment_records(environment, deployed_at)` 当前已存在，可优先复用

任何 index 追加都不能改写业务语义，也不能成为新增表的理由。

---

## 4. 新增应用层枚举 / 分类 Freeze

### 4.1 `OpsDiagnosticSampleKind`

固定值：

- `answer_session`
- `document_pipeline`

语义：

- `answer_session`
  - 一次 answer session 的诊断样本，source 指向 `answer_sessions.id`
- `document_pipeline`
  - 一个 document/indexing 链路诊断样本，source 指向 `documents.id`

冻结结论：

1. `deployment_compare_window` 不是 sample kind。
2. 不新增通用 `log_event` 或 `ops_event` sample kind。

### 4.2 `OpsDiagnosticOrigin`

固定值：

- `trend`
- `incident_cluster`
- `release_compare`

语义：

- `trend`
  - 从趋势或治理摘要进入样本列表
- `incident_cluster`
  - 从 incident cluster 进入样本列表
- `release_compare`
  - 从 deployment compare 进入样本列表

### 4.3 `OpsRegressionClass`

固定值：

- `new_regression`
- `existing_debt`
- `unknown`

语义：

- `new_regression`
  - after window 中出现、before window 没有同类可见样本或同类失败形态
- `existing_debt`
  - before window 已存在相同对象或相同失败形态
- `unknown`
  - 证据不足，不能稳定归类

冻结结论：

1. `OpsRegressionClass` 只存在于 read model，不进入 DB enum。
2. `new_regression` 不代表自动 rollback，只代表人工调查优先级。

### 4.4 `OpsReplayFreshnessFlag`

固定值：

- `stale_document`
- `citation_unready`
- `retrieval_scope_empty`
- `unknown`

语义：

- 空数组表示没有发现 freshness flag。
- 不使用 `none` 作为数组值。

---

## 5. Shared Types Freeze

### 5.1 新增导出类型

`packages/shared-types` 在 `P0` 必须新增并稳定导出：

- `OpsDiagnosticSampleKind`
- `OpsDiagnosticOrigin`
- `OpsRegressionClass`
- `OpsReplayFreshnessFlag`
- `OpsReplayRef`
- `OpsDiagnosticSample`
- `OpsDiagnosticSampleListQuery`
- `OpsDiagnosticSampleListResponse`
- `OpsAnswerSessionReplayRelatedContext`
- `OpsAnswerSessionReplayResponse`
- `OpsDocumentReplayRelatedContext`
- `OpsDocumentReplayResponse`
- `OpsDeploymentCompareQuery`
- `OpsDeploymentCompareDeployment`
- `OpsDeploymentCompareBaseline`
- `OpsDeploymentCompareWindow`
- `OpsDeploymentCompareDeltaSummary`
- `OpsDeploymentCompareResponse`

继续复用既有导出类型：

- `IncidentSeverity`
- `OpsTrendWindow`
- `OpsReadinessBlockingReason`
- `AnswerSessionResponse`
- `AnswerRetrievalTraceResponse`
- `DocumentDetail`
- `DocumentTimelineResponse`
- `DocumentEvidenceResponse`
- `DeploymentSmokeStatus`

### 5.2 固定 type aliases

```ts
export type OpsDiagnosticSampleKind = "answer_session" | "document_pipeline";

export type OpsDiagnosticOrigin = "trend" | "incident_cluster" | "release_compare";

export type OpsRegressionClass = "new_regression" | "existing_debt" | "unknown";

export type OpsReplayFreshnessFlag =
  | "stale_document"
  | "citation_unready"
  | "retrieval_scope_empty"
  | "unknown";
```

### 5.3 `OpsReplayRef`

```ts
export interface OpsReplayRef {
  method: "GET";
  path: string;
}
```

约束：

- `path` 必须是 API path，不是 Web route。
- Web 可把它映射到内部路由，但不得把 Web route 写入 API contract。

### 5.4 `OpsDiagnosticSample`

```ts
export interface OpsDiagnosticSample {
  sample_id: string;
  sample_kind: OpsDiagnosticSampleKind;
  source_id: string;
  origin: OpsDiagnosticOrigin;
  severity: IncidentSeverity;
  detected_at: string;
  title: string;
  summary: string;
  related_incident_ref: string | null;
  related_deployment_record_id: string | null;
  regression_class: OpsRegressionClass | null;
  next_replay_ref: OpsReplayRef;
}
```

固定主键格式：

- `answer_session:{session_id}`
- `document:{document_id}`

冻结结论：

1. `sample_id` 必须确定性生成。
2. `source_id` 保存原始对象 id。
3. `next_replay_ref` 指向 `ops` replay API，不指向旧事实面路径。

### 5.5 `OpsDiagnosticSampleListQuery`

```ts
export interface OpsDiagnosticSampleListQuery {
  origin: OpsDiagnosticOrigin;
  sample_kind?: OpsDiagnosticSampleKind;
  window?: OpsTrendWindow;
  cluster_key?: string;
  deployment_record_id?: string;
  page?: number;
  page_size?: number;
}
```

查询约束：

- `origin` 必填。
- `window` 默认 `24h`。
- `page` 默认 `1`。
- `page_size` 默认 `20`，最大 `100`。
- 当 `origin=incident_cluster` 时，`cluster_key` 必填。
- 当 `origin=release_compare` 时，`deployment_record_id` 必填。

### 5.6 `OpsDiagnosticSampleListResponse`

```ts
export interface OpsDiagnosticSampleListResponse {
  generated_at: string;
  origin: OpsDiagnosticOrigin;
  window: OpsTrendWindow;
  page: number;
  page_size: number;
  total: number;
  items: OpsDiagnosticSample[];
}
```

### 5.7 `OpsAnswerSessionReplayResponse`

```ts
export interface OpsAnswerSessionReplayRelatedContext {
  related_incident_ref: string | null;
  related_deployment_record_id: string | null;
  related_evaluation_run_ref: string | null;
  freshness_flags: OpsReplayFreshnessFlag[];
}

export interface OpsAnswerSessionReplayResponse {
  generated_at: string;
  sample: OpsDiagnosticSample;
  session: AnswerSessionResponse;
  retrieval: AnswerRetrievalTraceResponse;
  related_context: OpsAnswerSessionReplayRelatedContext;
}
```

冻结结论：

1. `session` 直接复用 `AnswerSessionResponse`。
2. `retrieval` 直接复用 `AnswerRetrievalTraceResponse`。
3. 不展示 prompt、chain-of-thought 或 provider 原始请求。

### 5.8 `OpsDocumentReplayResponse`

```ts
export interface OpsDocumentReplayRelatedContext {
  blocking_reason: OpsReadinessBlockingReason | null;
  related_incident_ref: string | null;
  related_answer_session_count: number;
  related_deployment_record_id: string | null;
}

export interface OpsDocumentReplayResponse {
  generated_at: string;
  sample: OpsDiagnosticSample;
  document: DocumentDetail;
  timeline: DocumentTimelineResponse;
  evidence: DocumentEvidenceResponse;
  related_context: OpsDocumentReplayRelatedContext;
}
```

冻结结论：

1. `document / timeline / evidence` 直接复用既有 document DTO。
2. `related_answer_session_count` 允许首版返回 `0`，但字段必须保留。
3. document replay 是只读视图，不触发 retry/reindex。

### 5.9 `OpsDeploymentCompareResponse`

```ts
export interface OpsDeploymentCompareQuery {
  deployment_record_id: string;
  window?: OpsTrendWindow;
  sample_kind?: OpsDiagnosticSampleKind;
}

export interface OpsDeploymentCompareDeployment {
  deployment_record_id: string;
  environment: string;
  commit_sha: string | null;
  workflow_run_id: string | null;
  current_image_tag: string;
  previous_stable_image_tag: string | null;
  smoke_status: DeploymentSmokeStatus;
  smoke_at: string | null;
  deployed_at: string;
  evidence_url: string | null;
}

export interface OpsDeploymentCompareBaseline {
  previous_stable_image_tag: string | null;
  previous_deployment_record_id: string | null;
  related_evaluation_run_ref: string | null;
}

export interface OpsDeploymentCompareWindow {
  start_at: string;
  end_at: string;
  sample_count: number;
  high_severity_count: number;
}

export interface OpsDeploymentCompareDeltaSummary {
  regression_count: number;
  new_regression_count: number;
  existing_debt_count: number;
  affected_answer_session_count: number;
  affected_document_count: number;
  summary: string;
}

export interface OpsDeploymentCompareResponse {
  generated_at: string;
  deployment: OpsDeploymentCompareDeployment;
  baseline: OpsDeploymentCompareBaseline;
  before_window: OpsDeploymentCompareWindow;
  after_window: OpsDeploymentCompareWindow;
  delta_summary: OpsDeploymentCompareDeltaSummary;
  affected_samples: OpsDiagnosticSample[];
}
```

冻结结论：

1. `deployment_record_id` 必填。
2. `window` 默认 `24h`，并表示 deployment 前后等宽窗口。
3. `affected_samples` 复用 `OpsDiagnosticSample`，不创建 deployment 专用 item。

---

## 6. API Contract Freeze

### 6.1 `GET /api/v1/ops/samples`

查询：

- `origin`: required, enum `OpsDiagnosticOrigin`
- `sample_kind`: optional, enum `OpsDiagnosticSampleKind`
- `window`: optional, enum `OpsTrendWindow`, default `24h`
- `cluster_key`: optional string
- `deployment_record_id`: optional uuid
- `page`: optional positive integer, default `1`
- `page_size`: optional positive integer, default `20`, max `100`

响应：

- `OpsDiagnosticSampleListResponse`

错误：

- `400`
  - unsupported enum
  - invalid pagination
  - missing `cluster_key` for `origin=incident_cluster`
  - missing `deployment_record_id` for `origin=release_compare`

### 6.2 `GET /api/v1/ops/replays/answer-sessions/:sessionId`

路径参数：

- `sessionId`: uuid

响应：

- `OpsAnswerSessionReplayResponse`

错误：

- `404`
  - answer session 不存在

### 6.3 `GET /api/v1/ops/replays/documents/:documentId`

路径参数：

- `documentId`: uuid

响应：

- `OpsDocumentReplayResponse`

错误：

- `404`
  - document 不存在

### 6.4 `GET /api/v1/ops/deployments/compare`

查询：

- `deployment_record_id`: required uuid
- `window`: optional, enum `OpsTrendWindow`, default `24h`
- `sample_kind`: optional, enum `OpsDiagnosticSampleKind`

响应：

- `OpsDeploymentCompareResponse`

错误：

- `400`
  - missing or invalid `deployment_record_id`
  - unsupported enum
- `404`
  - deployment record 不存在

### 6.5 既有路径保持兼容

以下路径不重命名、不改响应语义：

- `GET /api/v1/answers/:sessionId`
- `GET /api/v1/answers/:sessionId/retrieval`
- `GET /api/v1/documents/:documentId`
- `GET /api/v1/documents/:documentId/evidence`
- `GET /api/v1/documents/:documentId/timeline`
- `GET /api/v1/ops/overview`
- `GET /api/v1/ops/trends`
- `GET /api/v1/ops/incidents`
- `GET /api/v1/ops/deployments/latest`

---

## 7. SDK / Web Contract Freeze

### 7.1 API client 必须新增

`packages/api-client` 必须新增：

- `fetchOpsDiagnosticSamples(query)`
- `fetchOpsAnswerSessionReplay(sessionId)`
- `fetchOpsDocumentReplay(documentId)`
- `fetchOpsDeploymentCompare(query)`

### 7.2 Web 入口边界

`apps/web` 的 `P0` 入口固定为：

- `ops` 主板保留 overview/trends 首屏
- 样本列表、answer replay、document replay、deployment compare 都属于 `ops` 内部诊断工作流
- `Ask / Search / Detail` 最多通过现有 `OpsGovernanceNotice` 给出轻量跳转，不承载 replay payload

冻结结论：

1. Web 不允许自己发明 `sample_id` 规则。
2. Web 不允许自己判断 `new_regression / existing_debt`。
3. Web 不允许把 replay DTO 直接塞进终端问答页。

---

## 8. 实现前必须保持的禁止事项

`Phase 3A / P0` 不做：

- `POST /api/v1/ops/*`
- `POST /api/v1/evals/*`
- `POST /api/v1/deployments/*`
- `/api/v1/logs/*`
- 自动 retry、自动 reindex、自动 rollback
- 新增评测管理平台
- 团队协作、权限、通知编排
- prompt 或 chain-of-thought 展示

---

## 9. Freeze-Ready 结论

1. `Phase 3A / P0` contract 已可冻结。
2. 当前没有新增 schema blocker。
3. 下一步进入 `implementation-freeze`，把上述 contract 映射到文件写入范围、lane 顺序和测试矩阵。
4. 实现阶段若要偏离本文，必须先更新本文和对应 `tech/api`、`tech/data-model` 或 `docs/status`，不能直接改代码。
