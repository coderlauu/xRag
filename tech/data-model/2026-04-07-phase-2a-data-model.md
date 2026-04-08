# xRag Phase 2A Data Model

**日期：** 2026-04-07  
**版本：** `v4 / Phase 2A`  
**状态：** freeze-ready  
**适用范围：** `P0-01 ~ P0-07` 实现冻结基线  
**对应架构：** [Phase 2A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-2a-architecture.md)  
**对应决策：** [Phase 2A P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-p0-technical-tradeoffs.md)  
**对应冻结：** [Phase 2A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md)

---

## 1. 设计目标

在 `Phase 1C` 当前数据库基线上，补齐以下能力：

- 文档进入问答索引的 readiness / freshness
- chunk 与结构化 citation
- answer session 与 retrieval trace
- 不污染当前 parse / upload / timeline 主链路

---

## 2. 当前基线约束

当前 schema 已经具备：

- `documents`
- `document_parse_jobs`
- `document_processing_events`
- `document_source_fetches`
- `uploads / upload_parts`
- `tags / document_tags`

这意味着 `Phase 2A` 不应把已有解析链路拆掉重做，而应：

1. 继续以 `documents` 为内容事实源
2. 继续以 `document_parse_jobs` 和 `document_processing_events` 为文档异步处理证据
3. 为问答补新的“索引态”和“会话态”，而不是改写上传和解析态

---

## 3. 数据建模原则

1. `parse_status` 与 `index_status` 分离
2. `documents` 仍是主事实源，`chunks / citations / retrieval` 都从它派生
3. `answer_session` 是独立对象，不复用 `document_parse_jobs`
4. citation 必须指向稳定 `chunk_id`
5. `P0` 的离线评估集先不做进生产数据库

---

## 4. 推荐增量模型

### 4.1 `documents` 增量字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `index_status` | `enum('not_indexed','queued','chunking','embedding','ready','failed','stale')` | 文档进入问答索引的状态 |
| `index_version` | `varchar(64) nullable` | 当前 chunk/embedding 策略版本 |
| `indexed_at` | `timestamptz nullable` | 最近一次成功进入 ready 的时间 |
| `citation_ready` | `boolean not null default false` | 当前是否具备可用 citation 所需 chunk 和 locator |

说明：

- 现有 `diagnosis_code / diagnosis_summary` 继续复用，承载最近一次索引侧失败码即可，不单独再加一套 document-level index diagnosis 字段。
- `parse_status=success` 不代表 `index_status=ready`。

### 4.2 `document_parse_jobs` 增量 job type

当前 `document_parse_jobs` 仍继续用于“文档侧”异步任务，但扩展以下 job type：

- `chunk_document`
- `embed_document`

说明：

- `P0` 不建议为文档索引再造一张新 jobs 表。
- 这些 job 仍然是“围绕 document 的异步处理”，适合留在当前表。

### 4.3 `document_processing_events` 增量 stage

新增：

- `index`

示例 event type：

- `index_queued`
- `chunk_started`
- `chunk_succeeded`
- `embedding_started`
- `embedding_failed`
- `index_ready`

说明：

- `P0` 不需要再把 `chunk` 和 `embedding` 拆成新的 stage enum，使用 `stage=index` + 不同 `event_type` 即可。

### 4.4 新增 `document_chunks`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `document_id` | `uuid` | FK -> documents.id |
| `chunk_index` | `integer` | 文档内顺序 |
| `strategy_version` | `varchar(64)` | chunk 策略版本 |
| `section_label` | `varchar(128) nullable` | 标题或逻辑区域 |
| `page_ref` | `varchar(64) nullable` | PDF 页码或其他定位 |
| `content_text` | `text` | chunk 正文 |
| `token_count` | `integer` | 估算 token 数 |
| `content_sha256` | `char(64)` | chunk 内容指纹 |
| `embedding` | `vector(1536) nullable` | `P0` 固定单一 embedding 维度 |
| `citation_locator` | `jsonb nullable` | 前端回跳定位 |
| `created_at` | `timestamptz` | 创建时间 |

索引建议：

- `(document_id, chunk_index)`
- `hnsw` 或 `ivfflat` on `embedding`

### 4.5 新增 `answer_sessions`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `owner_id` | `uuid nullable` | 与现有单用户模型保持兼容 |
| `queue_job_id` | `varchar(128) nullable` | BullMQ job id |
| `question` | `text` | 用户问题 |
| `scope_mode` | `enum('global','search_result','document')` | `P0` 范围模式 |
| `scope_payload` | `jsonb` | scope 具体值和 snapshot |
| `retrieval_mode` | `enum('hybrid')` | `P0` 固定为 hybrid，但仍保留字段 |
| `status` | `enum('idle','retrieving','synthesizing','answered','needs_scope','refused','failed')` | 会话状态 |
| `answer_summary` | `text nullable` | 返回给前端的回答摘要 |
| `refusal_reason` | `text nullable` | 拒答说明 |
| `diagnosis_code` | `varchar(64) nullable` | 问答故障或拒答分类 |
| `provider_name` | `varchar(64) nullable` | answer provider 标识 |
| `provider_model` | `varchar(128) nullable` | 具体模型 |
| `latency_ms` | `integer nullable` | 端到端耗时 |
| `prompt_tokens` | `integer nullable` | prompt token |
| `completion_tokens` | `integer nullable` | completion token |
| `total_cost_usd` | `numeric(10,4) nullable` | 成本估算 |
| `created_at` | `timestamptz` | 创建时间 |
| `finished_at` | `timestamptz nullable` | 结束时间 |

说明：

- 即使 `问题历史` 被列为 `P1`，`P0` 仍需要持久化 answer session 作为 trace 事实源。
- 持久化 session 不等于产品默认开放完整历史页。

### 4.6 新增 `retrieval_runs`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `session_id` | `uuid` | FK -> answer_sessions.id |
| `query_normalized` | `text` | 规范化 query |
| `eligible_document_count` | `integer` | scope 内 eligible docs 数 |
| `lexical_hit_count` | `integer` | 关键词 hit 数 |
| `semantic_hit_count` | `integer` | 语义 hit 数 |
| `merged_hit_count` | `integer` | 合并后数量 |
| `rerank_strategy` | `varchar(64)` | rerank 策略版本 |
| `latency_ms` | `integer nullable` | retrieval 耗时 |
| `created_at` | `timestamptz` | 创建时间 |

### 4.7 新增 `retrieval_run_hits`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `retrieval_run_id` | `uuid` | FK -> retrieval_runs.id |
| `document_id` | `uuid` | FK -> documents.id |
| `chunk_id` | `uuid nullable` | FK -> document_chunks.id |
| `rank` | `integer` | 最终排序位次 |
| `lexical_score` | `numeric(10,4) nullable` | 关键词得分 |
| `semantic_score` | `numeric(10,4) nullable` | 向量得分 |
| `final_score` | `numeric(10,4) nullable` | merge/rerank 后得分 |
| `used_in_answer` | `boolean not null default false` | 是否进入 answer pack |
| `exclusion_reason` | `varchar(64) nullable` | 未入答原因 |
| `created_at` | `timestamptz` | 创建时间 |

### 4.8 新增 `answer_citations`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `session_id` | `uuid` | FK -> answer_sessions.id |
| `document_id` | `uuid` | FK -> documents.id |
| `chunk_id` | `uuid` | FK -> document_chunks.id |
| `claim_slot` | `varchar(64)` | 答案中的 claim 位置 |
| `quote_text` | `text` | 服务端重建后的引用原文 |
| `locator` | `jsonb` | 页面回跳定位 |
| `created_at` | `timestamptz` | 创建时间 |

---

## 5. 明确不建议进入主 schema 的内容

`P0` 不建议新增：

- `evaluation_cases`
- `evaluation_runs`
- `chat_messages`
- `conversation_threads`

理由：

- 评估集当前更适合作为 repo 资产管理
- 多轮会话和问题历史属于 `P1` 之后再评估的范围

---

## 6. 状态机建议

### 6.1 文档问答索引状态

`not_indexed -> queued -> chunking -> embedding -> ready`

失败与回补：

- `chunking -> failed`
- `embedding -> failed`
- 内容更新或策略变化后 `ready -> stale -> queued`

规则：

- `parse_status=success` 后才允许进入 `queued`
- `failed` 或 `stale` 文档不应被当作可引用证据

### 6.2 问答会话状态

`idle -> retrieving -> synthesizing -> answered | needs_scope | refused | failed`

规则：

- `needs_scope` 表示存在潜在证据，但当前范围过宽或冲突
- `refused` 表示当前范围下证据不足
- `failed` 表示基础设施、provider 或内部异常

---

## 7. 诊断码建议

新增：

- `index_chunk_failed`
- `index_embedding_failed`
- `retrieval_no_hits`
- `retrieval_scope_empty`
- `answer_insufficient_evidence`
- `citation_missing`
- `provider_timeout`

说明：

- 文档级最新索引问题可继续写回 `documents.diagnosis_code`
- answer session 级问题写入 `answer_sessions.diagnosis_code`

---

## 8. 迁移顺序建议

1. 启用 `pgvector` 扩展
2. 为 `documents` 增量添加 `index_status / index_version / indexed_at / citation_ready`
3. 扩展 `job_type` 和 `processing_event_stage`
4. 创建 `document_chunks / answer_sessions / retrieval_runs / retrieval_run_hits / answer_citations`
5. 为现有 `parse_status=success` 文档做异步 backfill

---

## 9. 保留与清理建议

- `document_chunks` 跟随 document 生命周期保留
- `answer_sessions` 建议至少保留 `30-90` 天
- `retrieval_run_hits` 建议至少保留 `30` 天
- `answer_citations` 与 `answer_sessions` 同步保留

---

## 10. Freeze 结论

以下事项已在 [Phase 2A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md) 中固定：

1. `embedding` 维度在 `P0` 锁定为首版 provider 的固定值 `1536`
2. `search_result` scope snapshot 的 `document_ids` 上限固定为 `100`
3. `reindex` 保持独立动作，并对普通用户最小开放
4. `answer_sessions` 继续作为正式持久化对象，为后续 ops 聚合与问题历史预留事实源
5. `documents.diagnosis_code` 继续承载最新索引失败码，不拆新字段
6. `ops/answer-summary` 在 `P0` 直接从基础表聚合，不新增物化层

本文件后续若需变更，应先更新 contract freeze，再改 schema 与 migration。
