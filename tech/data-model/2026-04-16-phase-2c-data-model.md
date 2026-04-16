# Phase 2C Data Model

**日期：** 2026-04-16
**版本：** `v6 / Phase 2C`
**状态：** draft
**对应文档：**
- [Phase 2C PRD](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-prd.md)
- [Phase 2C Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-backlog.md)
- [Phase 2C Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-architecture.md)
- [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)

---

## 1. 本文档目的

这份文档用于回答 `Phase 2C / P0` 的数据面问题：

- 哪些治理能力继续复用现有表聚合
- 哪些治理能力需要最小新增持久事实
- 哪些先明确不进入主 schema

本阶段不写 migration 细节，但要冻结数据面方向。

---

## 2. 当前事实与缺口

### 2.1 现有可直接复用的表

| 能力 | 当前事实源 | 当前情况 |
| --- | --- | --- |
| corpus readiness | `documents` | 已有 `index_status / indexed_at / imported_at / citation_ready` |
| runtime quality | `answer_sessions`, `answer_citations` | 已可聚合 `latency / cost / citation coverage / refusal rate` |
| runtime incidents | `document_parse_jobs`, `uploads` | 已可覆盖 `upload / parse / ocr / fetch / projection` |
| service health | 当前无持久化表，API 即时探测 | 可继续保持 read-only runtime check |

### 2.2 当前明确缺失的事实

| 能力 | 缺失点 | 影响 |
| --- | --- | --- |
| evaluation quality | 没有持久化 `groundedness / refusal_precision / recall / mrr` | 不能做正式质量板和趋势 |
| deployment history | 只有环境变量形式的 latest deployment | 不能做发布关联和历史对比 |
| deploy / ci incident history | 无稳定持久来源 | `IncidentSource` 中的 `deploy / ci` 暂时只有枚举预留 |

---

## 3. `Phase 2C` 数据面推荐

### 3.1 继续直接聚合，不新增专门表

以下能力继续从现有主表即时聚合：

- readiness snapshot
  - `documents.index_status`
  - `documents.indexed_at`
  - `documents.imported_at`
- runtime quality
  - `answer_sessions.status`
  - `answer_sessions.latency_ms`
  - `answer_sessions.total_cost_usd`
  - `answer_citations.session_id`
- runtime incident
  - `document_parse_jobs`
  - `uploads`

**结论**

- `Phase 2C / P0` 不新增：
  - `ops_metrics_daily`
  - `ops_dashboard_snapshots`
  - `incident_clusters`

### 3.2 新增 `evaluation_runs`

`evaluation_runs` 用于持久化离线或准离线评估运行结果，作为 `evaluation quality` 的正式事实源。

#### 推荐字段

| 字段 | 类型建议 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `run_ref` | `varchar(64)` | 展示或外部引用 ID |
| `environment` | `varchar(32)` | `ci / local / staging / production` |
| `source` | `varchar(32)` | `ci / manual / nightly` |
| `status` | `enum` | `running / completed / failed` |
| `commit_sha` | `char(40)` | 对应提交 |
| `dataset_version` | `varchar(64)` | golden set 或评估集版本 |
| `recall_at_10` | `numeric(6,4)` nullable | retrieval 结果 |
| `mrr` | `numeric(6,4)` nullable | retrieval 结果 |
| `hit_in_answer_rate` | `numeric(6,4)` nullable | retrieval 结果 |
| `groundedness` | `numeric(6,4)` nullable | answer quality |
| `citation_coverage` | `numeric(6,4)` nullable | answer quality |
| `refusal_precision` | `numeric(6,4)` nullable | answer quality |
| `latency_p95_ms` | `integer` nullable | 评估运行时观测 |
| `avg_token_cost_usd` | `numeric(12,4)` nullable | 评估运行时观测 |
| `embedding_backlog` | `integer` nullable | 运行态快照，可选 |
| `freshness_lag_p95_ms` | `integer` nullable | 运行态快照，可选 |
| `artifact_url` | `text` nullable | 评估产物链接 |
| `created_at` | `timestamptz` | 运行创建时间 |
| `completed_at` | `timestamptz` nullable | 完成时间 |

#### 推荐索引

- `idx_evaluation_runs_environment_completed_at`
- `idx_evaluation_runs_commit_sha`
- `idx_evaluation_runs_status`

#### 设计理由

- `Phase 2A` 当时刻意不把评估做成产品能力；但 `Phase 2C` 已把评估结果产品化，继续只靠 repo 文档无法支撑时间序列和对比。
- 指标数量有限，显式列优于 JSON 聚合字段，后续查询和 contract freeze 更稳定。

### 3.3 新增 `deployment_records`

`deployment_records` 用于持久化 release guard 所需的最小发布事实。

#### 推荐字段

| 字段 | 类型建议 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `environment` | `varchar(32)` | 例如 `production` |
| `commit_sha` | `char(40)` | 对应提交 |
| `workflow_run_id` | `varchar(32)` nullable | GitHub Actions run id |
| `current_image_tag` | `text` | 当前镜像 tag |
| `previous_stable_image_tag` | `text` nullable | 上一稳定 tag |
| `smoke_status` | `enum` | `passed / failed / unknown` |
| `smoke_at` | `timestamptz` nullable | smoke 结束时间 |
| `deployed_at` | `timestamptz` | 实际部署完成时间 |
| `evidence_url` | `text` nullable | deploy evidence 链接 |
| `created_at` | `timestamptz` | 记录写入时间 |

#### 推荐索引

- `idx_deployment_records_environment_deployed_at`
- `idx_deployment_records_commit_sha`
- `idx_deployment_records_workflow_run_id`

#### 设计理由

- 当前 `deployments/latest` 只看环境变量，无法支撑历史对比或关联 drift。
- `Phase 2C` 的 release guard 需要正式历史序列，但不需要完整的发布编排系统。

### 3.4 `deploy / ci` incident 的处理

`Phase 2C / P0` 不建议先建通用 incident 表。

推荐做法：

- deploy incident
  - 从 `deployment_records` 派生
- ci incident
  - 继续保留枚举位，但如果没有稳定事实源，可在 `P0` 保持空集

这样可以避免为了少量来源先抽象出完整事件仓库。

---

## 4. 关键语义约束

1. `refusal_rate` 与 `refusal_precision` 不是同一指标。
   - `refusal_rate`
     - 线上 runtime 比例
   - `refusal_precision`
     - evaluation quality 指标
2. `citation_coverage` 可以同时存在于 runtime 与 evaluation 视角，但必须标明来源。
3. `groundedness` 在 `Phase 2C / P0` 不从线上会话实时计算。
4. `deployment_records` 只记录发布事实，不承担自动回滚控制职责。
5. readiness 的结论仍需与 Ask 的真实 retrieval 行为一致，不能只存在表面统计。

---

## 5. 明确不建议进入主 schema 的内容

`Phase 2C / P0` 当前不建议新增：

- `ops_metrics_daily`
- `ops_dashboard_snapshots`
- `incident_clusters`
- `ops_incidents`
- `golden_set_cases`
- `golden_set_annotations`

理由：

- 这些对象要么会扩大 ingestion 面，要么把评测管理平台带进当前版本。
- 当前产品目标只要求治理面板成立，不要求完整 observability 平台。

---

## 6. 推荐迁移顺序

1. 新增 `evaluation_runs`
2. 新增 `deployment_records`
3. 冻结新的 `/ops` read model contract 与 shared types
4. 最后再考虑实现时是否需要把现有 `answer-summary` 做 additive 扩展

这样可以保证：

- 所有新增 migration 都是 additive
- 旧 `ops` 页面和旧客户端在短期内继续兼容
- `Phase 2C` 先把新增事实源补齐，再推进 API freeze
