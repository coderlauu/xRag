# Phase 3A Data Model

**日期：** 2026-04-17
**版本：** `v7 / Phase 3A`
**状态：** draft
**对应文档：**
- [Phase 3A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-prd.md)
- [Phase 3A Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-backlog.md)
- [Phase 3A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-architecture.md)
- [Phase 2C Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-16-phase-2c-data-model.md)
- [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)

---

## 1. 本文档目的

这份文档用于回答 `Phase 3A / P0` 的数据面问题：

- `diagnostic sample / replay / deployment compare` 各自沿用哪些现有表
- 本轮是否需要新增 durable core table
- 哪些只是 read model 派生，不应提前落成新 schema

本阶段不写 migration 细节，但要先冻结数据面方向。

---

## 2. 当前可直接复用的事实源

### 2.1 Answer session replay

| 能力 | 当前事实源 | 当前情况 |
| --- | --- | --- |
| session metadata | `answer_sessions` | 已有 `question / scope_mode / scope_payload / status / diagnosis_code / provider / cost / latency / finished_at` |
| retrieval trace | `retrieval_runs`, `retrieval_run_hits` | 已有 query 归一化、候选数、命中数、排序与 exclusion reason |
| evidence and citation | `answer_claims`, `answer_citations` | 已有 claim slot、freshness badge、quote、locator |

### 2.2 Document pipeline replay

| 能力 | 当前事实源 | 当前情况 |
| --- | --- | --- |
| document summary | `documents` | 已有 `parse_status / index_status / citation_ready / diagnosis / imported_at / indexed_at` |
| pipeline timeline | `document_processing_events` | 已有 `stage / status / diagnosis_code / summary / created_at` |
| parse / fetch detail | `document_parse_jobs`, `document_source_fetches` | 已有 job status、error、incident ref、http 结果等 |
| evidence preview | `document_chunks` | 已有 chunk 内容与 citation locator |

### 2.3 Deployment compare

| 能力 | 当前事实源 | 当前情况 |
| --- | --- | --- |
| deployment anchor | `deployment_records` | 已有 `environment / commit_sha / workflow_run_id / image tag / smoke_status / deployed_at` |
| evaluation context | `evaluation_runs` | 已有 `commit_sha`、quality metrics、artifact_url |
| before/after answer sample | `answer_sessions` | 可按时间窗口筛选 |
| before/after document sample | `documents`, `document_processing_events` | 可按状态与时间窗口筛选 |

---

## 3. 数据面结论

### 3.1 `Phase 3A / P0` 默认不新增 durable core table

**结论**

- `diagnostic_sample` 不建表。
- `answer_session_replay` 不建表。
- `document_pipeline_replay` 不建表。
- `deployment_compare_window` 不建表。

**原因**

- 当前 `Phase 2C` 之后，真正缺的是“把事实链组织成可读对象”，不是“再补一层事实存储”。
- 现有表已经覆盖 `P0` 所需的 session、document、deployment 三条链。
- 过早建表会把 `Phase 3A` 从产品化诊断拉向新的 ingestion 平台。

### 3.2 `diagnostic_sample` 的正式身份是派生对象

**推荐最小字段**

- `sample_id`
- `sample_kind`
- `source_id`
- `origin`
- `severity`
- `detected_at`
- `summary`
- `related_incident_ref`
- `related_deployment_record_id`

**字段来源**

- `answer_session` 样本
  - `source_id` = `answer_sessions.id`
  - `detected_at` = `coalesce(answer_sessions.finished_at, answer_sessions.updated_at, answer_sessions.created_at)`
- `document_pipeline` 样本
  - `source_id` = `documents.id`
  - `detected_at` = `coalesce(documents.indexed_at, documents.updated_at, documents.imported_at)`

**语义约束**

- `sample_id` 必须是确定性派生，不允许生成一次性 UUID。
- 相同 session 或 document 从不同入口进入时，必须得到同一 `sample_id`。

### 3.3 deployment compare 的对象锚点

**结论**

- deployment compare 的 anchor 统一使用 `deployment_records.id`。
- `workflow_run_id`、`commit_sha`、镜像 tag 都是 compare 上下文，不是 compare 主键。

**原因**

- `deployment_records.id` 是当前唯一稳定、可数据库索引、可内部引用的部署事实主键。
- 如果直接把 compare 锚在 `workflow_run_id` 或镜像 tag，上下游兼容性更差。

### 3.4 “新回归” 的数据面表达

`Phase 3A / P0` 不新建回归表，先采用窗口对比派生：

- session 样本
  - 同类 `sample_kind + source_id` 仅出现在 after window
  - 或 after window 中 severity / failure shape 明显高于 before window
- document 样本
  - `failed / stale / backlog` 状态首次在 after window 出现
  - 或同一 document 在 after window 出现新的阻断状态

**结论**

- “新回归” 在 `P0` 是 compare read model 的解释字段，不是新的 durable 状态枚举。

### 3.5 当前允许的 schema 变化边界

`technical-evaluation` 的默认结论是：

- 不新增新表
- 不新增新枚举
- 不改写 `answer_session_status / index_status / deployment_smoke_status` 语义

若后续实现证明存在性能瓶颈，允许在 `contract-freeze` 后讨论的仅限：

- additive index
- additive nullable helper column

这些优化不能改写对象语义，且必须单独说明为什么现有查询无法支撑。

---

## 4. 需要重点盯住的查询热点

虽然 `P0` 默认不加新表，但后续实现需要注意三类查询热点：

1. 按时间窗口筛 `answer_sessions`
   - 当前有 `created_at` 索引，但若回放主语义固定到 `finished_at`，可能需要额外索引评估
2. 围绕单 document 拉 timeline
   - 当前 `document_processing_events(document_id, created_at)` 已有索引，首版可直接复用
3. 围绕 deployment compare 扫 before/after 样本
   - 当前 `deployment_records(environment, deployed_at)`、`evaluation_runs(environment, completed_at)` 已可作为 anchor 入口

结论：

- 当前没有必须先做的 schema blocker。
- 性能问题应在 implementation freeze 结合真实查询计划再决定，不应在 `technical-evaluation` 阶段预先扩表。

---

## 5. Freeze-Ready 结论

1. `Phase 3A / P0` 当前可以在零新表前提下成立。
2. `diagnostic_sample` 是 read model，不是持久表。
3. deployment compare 的主锚点应固定为 `deployment_records.id`。
4. “新回归” 先是 compare 派生结论，不进入主 schema 枚举。
5. 后续 contract freeze 的重点不再是“要不要建表”，而是“如何冻结派生对象、查询窗口和资源契约”。
