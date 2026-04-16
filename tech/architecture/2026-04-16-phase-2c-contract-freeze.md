# xRag Phase 2C Contract Freeze

**日期：** 2026-04-16
**版本：** `v6 / Phase 2C`
**状态：** freeze-ready
**适用范围：** `P0-01 ~ P0-04`
**对应文档：**
- [Phase 2C Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-architecture.md)
- [Phase 2C Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-16-phase-2c-data-model.md)
- [Phase 2C API Design](/Users/coderlauu/xRag/tech/api/2026-04-16-phase-2c-api.md)
- [Phase 2C Contract Freeze Prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-16-phase-2c-contract-freeze-prerequisites.md)
- [Phase 2C PRD](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-prd.md)
- [Phase 2C Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-backlog.md)
- [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
- [Phase 2B Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-13-phase-2b-contract-freeze.md)

---

## 1. 文档目的

这份文档把 `Phase 2C / P0` 进入实现前最需要稳定的五类 contract 固定下来：

1. `schema`
2. `shared-types`
3. `API contract`
4. `ops governance read model`
5. `runtime quality / evaluation quality / readiness / release guard` 语义

后续编码若与本文冲突，以本文为准；如需偏离，必须先回写文档，而不是在实现中临时改语义。

---

## 2. Source Of Truth 顺序

`Phase 2C / P0` 的 contract source-of-truth 顺序固定为：

1. [Phase 2C Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-contract-freeze.md)
2. [Phase 2C Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-16-phase-2c-data-model.md)
3. [Phase 2C API Design](/Users/coderlauu/xRag/tech/api/2026-04-16-phase-2c-api.md)
4. [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
5. `apps/api/src/database/schema.ts`
6. `packages/shared-types/src/index.ts`
7. OpenAPI / SDK / 实现代码

---

## 3. Schema Freeze

### 3.1 保持稳定的既有 enum

以下既有 enum 名称和值在 `Phase 2C / P0` 保持稳定，不重命名、不改语义：

| enum / type | 值 |
| --- | --- |
| `IncidentSource` | `upload`, `parse`, `ocr`, `fetch`, `projection`, `deploy`, `ci` |
| `IncidentSeverity` | `low`, `medium`, `high` |
| `IncidentStatus` | `open`, `tracked`, `resolved` |
| `DeploymentSmokeStatus` | `passed`, `failed`, `unknown` |
| `IndexStatus` | `not_indexed`, `queued`, `chunking`, `embedding`, `ready`, `failed`, `stale` |

冻结结论：

1. 不删除 `deploy / ci`，即使 `ci` 首版没有稳定数据。
2. 不新增 answer session status。
3. 不把治理风险状态写入 `answer_session_status`。

### 3.2 新增应用层枚举

`Phase 2C / P0` 必须新增并稳定以下应用层枚举。

#### `OpsReadinessBlockingReason`

固定值：

- `none`
- `no_ready_documents`
- `indexing_backlog`
- `indexing_failed`
- `stale_corpus`

语义：

- `none`
  - 当前 readiness 未阻断 Ask
- `no_ready_documents`
  - 当前没有 `index_status=ready` 且 `citation_ready=true` 的可引用文档
- `indexing_backlog`
  - 存在 `queued / chunking / embedding` 中的索引积压
- `indexing_failed`
  - 存在失败索引，且可能影响 Ask 范围
- `stale_corpus`
  - stale 文档占比或 freshness lag 已经影响可信度判断

#### `OpsRecommendedActionCode`

固定值：

- `inspect_indexing_backlog`
- `inspect_failed_documents`
- `run_backfill_indexing_dry_run`
- `inspect_quality_regression`
- `inspect_incident_cluster`
- `verify_latest_deployment`
- `rollback_to_previous_stable`
- `monitor_without_action`

冻结结论：

1. 这些 action 只是推荐动作，不表示自动执行。
2. `run_backfill_indexing_dry_run` 只能指向人工确认前的 dry-run，不得变成自动回补。

#### `OpsReleaseGuardRiskLevel`

固定值：

- `healthy`
- `warning`
- `critical`

语义：

- `healthy`
  - smoke、服务健康与质量摘要未显示明显风险
- `warning`
  - 有观察风险，但不要求立即回滚
- `critical`
  - smoke 失败、关键服务异常或质量指标明显回退，需要人工决策

#### `OpsTrendWindow`

固定值：

- `24h`
- `7d`
- `30d`

#### `OpsTrendSource`

固定值：

- `runtime`
- `evaluation`

#### `OpsTrendMetric`

固定值：

- `citation_coverage`
- `refusal_rate`
- `latency_p95_ms`
- `avg_token_cost_usd`
- `groundedness`
- `refusal_precision`
- `recall_at_10`
- `mrr`
- `hit_in_answer_rate`
- `embedding_backlog`
- `freshness_lag_p95_ms`

冻结结论：

1. `readiness_rate` 不进入 `Phase 2C / P0` 的 trends metric。
2. 当前 readiness 是 current overview snapshot，不承诺从现有 OLTP 表回放历史 readiness。
3. `embedding_backlog / freshness_lag_p95_ms` 只允许从 `evaluation_runs` 中的快照字段形成 `evaluation` source series。

### 3.3 新增 `evaluation_runs`

`Phase 2C / P0` 固定新增 `evaluation_runs`，作为 `evaluation_quality` 的唯一持久事实源。

固定字段：

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `id` | `uuid` | primary key |
| `run_ref` | `varchar(64)` | not null, unique |
| `environment` | `varchar(32)` | not null |
| `source` | `varchar(32)` | not null, application enum |
| `status` | `evaluation_run_status` | not null, default `running` |
| `commit_sha` | `char(40)` | nullable |
| `dataset_version` | `varchar(64)` | nullable |
| `recall_at_10` | `numeric(6,4)` | nullable |
| `mrr` | `numeric(6,4)` | nullable |
| `hit_in_answer_rate` | `numeric(6,4)` | nullable |
| `groundedness` | `numeric(6,4)` | nullable |
| `citation_coverage` | `numeric(6,4)` | nullable |
| `refusal_precision` | `numeric(6,4)` | nullable |
| `latency_p95_ms` | `integer` | nullable |
| `avg_token_cost_usd` | `numeric(12,4)` | nullable |
| `embedding_backlog` | `integer` | nullable |
| `freshness_lag_p95_ms` | `integer` | nullable |
| `artifact_url` | `text` | nullable |
| `error_message` | `text` | nullable |
| `created_at` | `timestamptz` | not null, default now |
| `completed_at` | `timestamptz` | nullable |

新增 DB enum：

- `evaluation_run_status`
  - `running`
  - `completed`
  - `failed`

推荐索引：

- `idx_evaluation_runs_environment_completed_at`
- `idx_evaluation_runs_commit_sha`
- `idx_evaluation_runs_status`

冻结结论：

1. `groundedness / refusal_precision / recall_at_10 / mrr / hit_in_answer_rate` 只来自 `evaluation_runs`。
2. 线上 runtime 会话不得直接伪造这些字段。
3. `citation_coverage` 在 `evaluation_runs` 中表示 evaluation source；不要和 runtime source 混写。

### 3.4 新增 `deployment_records`

`Phase 2C / P0` 固定新增 `deployment_records`，作为 release guard 的最小持久事实源。

固定字段：

| 字段 | 类型 | 约束 |
| --- | --- | --- |
| `id` | `uuid` | primary key |
| `environment` | `varchar(32)` | not null |
| `commit_sha` | `char(40)` | nullable |
| `workflow_run_id` | `varchar(32)` | nullable |
| `current_image_tag` | `text` | not null |
| `previous_stable_image_tag` | `text` | nullable |
| `smoke_status` | `deployment_smoke_status` | not null, default `unknown` |
| `smoke_at` | `timestamptz` | nullable |
| `deployed_at` | `timestamptz` | not null |
| `evidence_url` | `text` | nullable |
| `created_at` | `timestamptz` | not null, default now |

新增 DB enum：

- `deployment_smoke_status`
  - `passed`
  - `failed`
  - `unknown`

推荐索引：

- `idx_deployment_records_environment_deployed_at`
- `idx_deployment_records_commit_sha`
- `idx_deployment_records_workflow_run_id`

冻结结论：

1. `deployment_records` 只记录发布事实，不触发自动回滚。
2. 现有 `GET /ops/deployments/latest` 可以继续由环境变量兜底，但新的 release guard 应优先读取 `deployment_records` 最新记录。
3. 若没有 `deployment_records`，release guard 返回 `warning` 或 `unknown` 类摘要，不得伪造成功部署历史。

### 3.5 明确不新增的 schema

`Phase 2C / P0` 不新增：

- `ops_metrics_daily`
- `ops_dashboard_snapshots`
- `incident_clusters`
- `ops_incidents`
- `golden_set_cases`
- `golden_set_annotations`

---

## 4. Shared Types Freeze

### 4.1 新增导出类型

`packages/shared-types` 在 `P0` 必须新增并稳定导出：

- `OpsReadinessBlockingReason`
- `OpsRecommendedActionCode`
- `OpsReleaseGuardRiskLevel`
- `OpsTrendWindow`
- `OpsTrendSource`
- `OpsTrendMetric`
- `OpsReadinessSnapshot`
- `OpsRuntimeQualitySummary`
- `OpsEvaluationQualitySummary`
- `OpsIncidentCluster`
- `OpsIncidentSummaryBlock`
- `OpsReleaseGuard`
- `OpsRecommendedAction`
- `OpsGovernanceNotice`
- `OpsOverviewResponse`
- `OpsTrendPoint`
- `OpsTrendSeries`
- `OpsTrendsResponse`

现有导出类型继续保留：

- `OpsAnswerSummaryResponse`
- `OpsHealthSummaryResponse`
- `OpsIncidentListResponse`
- `LatestDeploymentResponse`

### 4.2 稳定 shape

`OpsOverviewResponse` 固定为：

```ts
export interface OpsOverviewResponse {
  generated_at: string;
  readiness: OpsReadinessSnapshot;
  runtime_quality: OpsRuntimeQualitySummary;
  evaluation_quality: OpsEvaluationQualitySummary | null;
  incident_summary: OpsIncidentSummaryBlock;
  release_guard: OpsReleaseGuard;
  recommended_actions: OpsRecommendedAction[];
  notices: OpsGovernanceNotice[];
}
```

`OpsReadinessSnapshot` 固定为：

```ts
export interface OpsReadinessSnapshot {
  queued_count: number;
  chunking_count: number;
  embedding_count: number;
  ready_count: number;
  stale_count: number;
  failed_count: number;
  total_count: number;
  readiness_rate: number | null;
  freshness_lag_p95_ms: number | null;
  blocking_reason: OpsReadinessBlockingReason;
}
```

`OpsRuntimeQualitySummary` 固定为：

```ts
export interface OpsRuntimeQualitySummary {
  window: OpsTrendWindow;
  terminal_session_count: number;
  answered_session_count: number;
  latency_p50_ms: number | null;
  latency_p95_ms: number | null;
  citation_coverage: number | null;
  refusal_rate: number | null;
  avg_token_cost_usd: string | null;
}
```

`OpsEvaluationQualitySummary` 固定为：

```ts
export interface OpsEvaluationQualitySummary {
  latest_run_ref: string;
  environment: string;
  source: string;
  status: "completed" | "failed";
  commit_sha: string | null;
  dataset_version: string | null;
  completed_at: string | null;
  recall_at_10: number | null;
  mrr: number | null;
  hit_in_answer_rate: number | null;
  groundedness: number | null;
  citation_coverage: number | null;
  refusal_precision: number | null;
  latency_p95_ms: number | null;
  avg_token_cost_usd: string | null;
}
```

`OpsIncidentSummaryBlock` 固定为：

```ts
export interface OpsIncidentSummaryBlock {
  open_count: number;
  high_risk_count: number;
  clusters: OpsIncidentCluster[];
}
```

`OpsIncidentCluster` 固定为：

```ts
export interface OpsIncidentCluster {
  cluster_key: string;
  source: IncidentSource;
  severity: IncidentSeverity;
  status: IncidentStatus;
  incident_count: number;
  latest_incident_ref: string | null;
  affected_surface: "upload" | "indexing" | "retrieval" | "answer" | "deployment" | "ci" | "ops";
  recommended_action_code: OpsRecommendedActionCode;
}
```

`OpsReleaseGuard` 固定为：

```ts
export interface OpsReleaseGuard {
  risk_level: OpsReleaseGuardRiskLevel;
  current_image_tag: string | null;
  previous_stable_image_tag: string | null;
  smoke_status: DeploymentSmokeStatus;
  smoke_at: string | null;
  deployed_at: string | null;
  workflow_run_id: string | null;
  related_evaluation_run_ref: string | null;
  related_incident_count: number;
  summary: string;
}
```

`OpsRecommendedAction` 固定为：

```ts
export interface OpsRecommendedAction {
  code: OpsRecommendedActionCode;
  priority: IncidentSeverity;
  surface: "ops" | "ask" | "search" | "detail" | "deployment" | "indexing" | "evaluation";
  title: string;
  summary: string;
}
```

`OpsGovernanceNotice` 固定为：

```ts
export interface OpsGovernanceNotice {
  target: "ask" | "search" | "detail";
  level: OpsReleaseGuardRiskLevel;
  code: OpsReadinessBlockingReason | OpsRecommendedActionCode;
  title: string;
  summary: string;
}
```

`OpsTrendsResponse` 固定为：

```ts
export interface OpsTrendsResponse {
  window: OpsTrendWindow;
  generated_at: string;
  series: OpsTrendSeries[];
}
```

`OpsTrendSeries` 固定为：

```ts
export interface OpsTrendSeries {
  metric: OpsTrendMetric;
  source: OpsTrendSource;
  granularity: "hour" | "day";
  points: OpsTrendPoint[];
}
```

`OpsTrendPoint` 固定为：

```ts
export interface OpsTrendPoint {
  ts: string;
  value: number | string | null;
}
```

### 4.3 命名规则

shared types 继续遵守当前 repo 约定：

1. TypeScript 类型使用 `PascalCase`
2. API JSON 字段使用 `snake_case`
3. enum 值使用稳定英文小写下划线
4. 金额字段如果来自 PostgreSQL `numeric`，API 继续使用字符串或 `null`

---

## 5. API Contract Freeze

### 5.1 保持不变

以下现有路径保持兼容，不改旧字段语义：

- `GET /api/v1/ops/health-summary`
- `GET /api/v1/ops/incidents`
- `GET /api/v1/ops/answer-summary`
- `GET /api/v1/ops/deployments/latest`

冻结结论：

1. `answer-summary.refusal_rate` 保持 runtime rate 语义。
2. 不把 `groundedness` 或 `refusal_precision` 塞入 `answer-summary`。
3. `incidents` 继续是列表资源，不承载 clusters。
4. `deployments/latest` 继续只读 latest，不承担 release guard 全量判断。

### 5.2 新增 `GET /api/v1/ops/overview`

固定新增：

- `GET /api/v1/ops/overview`

查询参数：

- 无必填参数

响应：

- `OpsOverviewResponse`

固定结论：

1. `overview` 是 `Phase 2C` 治理主板的主读模型。
2. API 负责生成 `blocking_reason / risk_level / recommended_actions / notices`。
3. Web 负责展示，不再自行发明治理判断。
4. 如果没有 evaluation run，`evaluation_quality` 返回 `null`，不得用 runtime 指标伪造。

### 5.3 新增 `GET /api/v1/ops/trends`

固定新增：

- `GET /api/v1/ops/trends`

查询参数：

- `window`
  - 可选
  - 默认 `7d`
  - 允许值：`24h | 7d | 30d`

响应：

- `OpsTrendsResponse`

固定结论：

1. trends 返回可稳定计算的 series，不保证每个 metric 在每个环境都有数据。
2. runtime source 首版只承载可从 `answer_sessions / answer_citations` 按时间窗口聚合的指标。
3. evaluation source 首版只承载 `evaluation_runs` 中存在的指标。
4. 不承诺从现有 OLTP 表回放历史 readiness rate。

### 5.4 不新增的 API

`Phase 2C / P0` 不新增：

- `/api/v1/evals/*`
- `/api/v1/ops/dashboard-snapshots`
- `/api/v1/ops/auto-remediation/*`
- `/api/v1/ops/incidents/clusters`

---

## 6. Governance / Answer Quality Freeze

### 6.1 Runtime quality 与 evaluation quality

冻结结论：

1. `runtime_quality.refusal_rate` 与 `evaluation_quality.refusal_precision` 是不同指标。
2. `evaluation_quality.groundedness` 只来自 `evaluation_runs`。
3. `citation_coverage` 可同时出现在 runtime 和 evaluation，但必须通过所属对象区分来源。
4. `Recall@K / MRR / hit_in_answer_rate` 只属于 evaluation source。

### 6.2 Readiness 与 Ask 行为

冻结结论：

1. readiness 只表达“当前资料是否具备可问基础”。
2. readiness 不改写 `AnswerSessionStatus`。
3. Ask 真正无证据时仍必须走 `needs_scope / refused` 等既有语义。
4. `no_ready_documents` 不得被包装成 provider failure。

### 6.3 Governance notice

冻结结论：

1. `Phase 2C / P0` 不给 `AnswerSessionResponse`、`DocumentDetail` 或 `Search` 响应新增治理字段。
2. `Ask / Search / Detail` 如需轻量提示，只能读取 `GET /ops/overview` 中的 `notices`。
3. notice 不改变用户提交问题、检索、引用、拒答的主 contract。
4. notice 只用于提示与跳转，不用于自动拦截问答。

### 6.4 Release guard

冻结结论：

1. release guard 只做关联判断，不自动回滚。
2. `critical` 不等于“系统已经自动降级”。
3. 若 smoke failed，应给出 `rollback_to_previous_stable` 推荐动作，但由人工执行。

---

## 7. 实现顺序约束

进入实现阶段时，主线程顺序固定为：

1. `schema + migration`
2. `shared-types`
3. `API DTO / OpenAPI / SDK`
4. `ops overview / trends service read model`
5. `web ops board`
6. `test / e2e / smoke`

在第 1-3 步完成前，不启动并行实现 lane。

---

## 8. Freeze 结论

1. `Phase 2C` 正式新增 `/ops/overview + /ops/trends`。
2. `Phase 2C` 正式新增 `evaluation_runs + deployment_records`。
3. `runtime quality` 与 `evaluation quality` 以 shared types 分区表达。
4. `Ask / Search / Detail` 不新增主 contract 字段，轻量治理提示统一来自 `/ops/overview.notices`。
5. 当前没有新的 contract blocker，可以进入 `implementation-freeze`。
