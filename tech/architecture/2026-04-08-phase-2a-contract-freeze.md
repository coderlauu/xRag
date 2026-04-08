# xRag Phase 2A Contract Freeze

**日期：** 2026-04-08  
**版本：** `v4 / Phase 2A`  
**状态：** freeze-ready  
**适用范围：** `P0-01 ~ P0-07`  
**对应文档：**
- [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)
- [Phase 2A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-2a-architecture.md)
- [Phase 2A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-2a-data-model.md)
- [Phase 2A API Design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-2a-api.md)
- [Phase 2A P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-08-phase-2a-p0-technical-tradeoffs.md)

---

## 1. 文档目的

这份文档把 `Phase 2A / P0` 进入实现前最需要稳定的四类 contract 固定下来：

1. `schema`
2. `shared-types`
3. `API contract`
4. `状态机 / citation / scope / diagnosis`

后续编码若与本文冲突，以本文为准；如需偏离，必须先回写文档，而不是在实现中临时改语义。

---

## 2. Source Of Truth 顺序

`Phase 2A / P0` 的 contract source-of-truth 顺序固定为：

1. [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)
2. [Phase 2A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md)
3. [Phase 2A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-2a-data-model.md)
4. [Phase 2A API Design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-2a-api.md)
5. `apps/api/src/database/schema.ts`
6. `packages/shared-types/src/index.ts`
7. OpenAPI / SDK / 实现代码

---

## 3. Schema Freeze

### 3.1 新增或扩展的 enum

以下 enum 名称和值在 `P0` 固定：

| enum | 值 |
| --- | --- |
| `index_status` | `not_indexed`, `queued`, `chunking`, `embedding`, `ready`, `failed`, `stale` |
| `job_type` 增量 | `chunk_document`, `embed_document` |
| `processing_event_stage` 增量 | `index` |
| `answer_scope_mode` | `global`, `search_result`, `document` |
| `retrieval_mode` | `hybrid` |
| `answer_session_status` | `idle`, `retrieving`, `synthesizing`, `answered`, `needs_scope`, `refused`, `failed` |

### 3.2 `documents` 增量字段

`documents` 在 `P0` 必须新增：

- `index_status`
- `index_version`
- `indexed_at`
- `citation_ready`

冻结结论：

1. `documents.diagnosis_code` 继续承载最近一次索引侧失败码，不新增第二套 document-level index diagnosis 字段。
2. `parse_status=success` 不代表 `index_status=ready`。
3. `failed / stale` 文档不得作为可引用证据。

### 3.3 新增表

`P0` 固定新增以下表：

1. `document_chunks`
2. `answer_sessions`
3. `retrieval_runs`
4. `retrieval_run_hits`
5. `answer_citations`

这些表名与主键/外键语义在 `P0` 不再改名。

### 3.4 冻结的关键字段

以下字段语义在 `P0` 固定：

- `document_chunks.embedding`
  - 类型：`vector(1536)`
- `answer_sessions.scope_payload`
  - 保存 scope 具体值与 snapshot
- `answer_sessions.queue_job_id`
  - 保存问答任务的 BullMQ job id
- `retrieval_run_hits.used_in_answer`
  - 表示该 hit 是否进入 answer candidate pack
- `answer_citations.quote_text`
  - 由服务端重建后的引用原文，不信任模型自由输出
- `answer_citations.locator`
  - 前端跳回定位信息

### 3.5 明确不进入主 schema

以下内容在 `P0` 明确不进入主 schema：

- `evaluation_cases`
- `evaluation_runs`
- `chat_messages`
- `conversation_threads`

### 3.6 聚合层结论

`GET /ops/answer-summary` 在 `P0` 直接从基础表聚合：

- `documents`
- `document_parse_jobs`
- `answer_sessions`
- 必要时补充 `retrieval_runs`

`P0` 不引入物化视图或额外 summary table。

---

## 4. Shared Types Freeze

### 4.1 新增导出类型

`packages/shared-types` 在 `P0` 必须新增并稳定导出：

- `IndexStatus`
- `AnswerSessionStatus`
- `AnswerScopeMode`
- `RetrievalMode`
- `AnswerScope`
- `AnswerCitation`
- `AnswerSessionResponse`
- `CreateAnswerRequest`
- `CreateAnswerResponse`
- `AnswerRetrievalTraceItem`
- `AnswerRetrievalTraceResponse`
- `DocumentEvidenceItem`
- `DocumentEvidenceResponse`
- `ReindexDocumentResponse`
- `OpsAnswerSummaryResponse`

### 4.2 现有类型的增量字段

以下现有类型在 `P0` 需要增量字段：

`DocumentSummary`
- `index_status`
- `indexed_at`
- `citation_ready`

`DocumentDetail`
- `index_status`
- `index_version`
- `indexed_at`
- `citation_ready`

`ListDocumentsQuery`
- `index_status`

`DocumentProcessingEventItem`
- `stage` 允许 `index`

### 4.3 诊断码扩展

`DiagnosisCode` 在 `P0` 固定新增：

- `index_chunk_failed`
- `index_embedding_failed`
- `retrieval_no_hits`
- `retrieval_scope_empty`
- `answer_insufficient_evidence`
- `citation_missing`
- `provider_timeout`

### 4.4 命名规则

shared types 继续遵守当前 repo 约定：

1. TypeScript 类型使用 `PascalCase`
2. API JSON 字段使用 `snake_case`
3. enum 值使用稳定英文小写下划线

---

## 5. API Contract Freeze

### 5.1 保持不变

- 基础前缀仍为 `/api/v1`
- `GET /documents` 继续是 keyword search 基线
- `GET /documents?q=...` 不引入问答语义

### 5.2 `documents` 资源增量

固定新增：

- `GET /api/v1/documents/{documentId}/evidence`
- `POST /api/v1/documents/{documentId}/reindex`

`POST /documents/{documentId}/reindex` 返回：

```json
{
  "document_id": "doc_123",
  "job_id": "job_123",
  "index_status": "queued",
  "diagnosis_code": null
}
```

### 5.3 `answers` 资源

固定新增：

- `POST /api/v1/answers`
- `GET /api/v1/answers/{sessionId}`
- `GET /api/v1/answers/{sessionId}/retrieval`

固定结论：

1. `POST /answers` 使用 `202 Accepted`
2. `GET /answers/{id}` 返回 terminal 或 running 状态
3. `GET /answers/{id}/retrieval` 在 `P0` 即为正式资源，不隐藏在日志里

### 5.4 `scope` contract

`scope.mode` 在 `P0` 只允许：

- `global`
- `search_result`
- `document`

`search_result` 的 `scope.payload` 固定包含：

- `document_ids`
- `truncated`

推荐同时包含：

- `query`
- `filters`
- `total`

`document_ids` 上限固定为 `100`。

### 5.5 `GET /answers/{id}` 的 terminal 语义

以下状态为 terminal：

- `answered`
- `needs_scope`
- `refused`
- `failed`

以下状态为 running：

- `idle`
- `retrieving`
- `synthesizing`

### 5.6 `ops` 资源

`P0` 固定新增：

- `GET /api/v1/ops/answer-summary`

它至少返回：

- `embedding_backlog`
- `ready_document_count`
- `stale_document_count`
- `answer_latency_p95`
- `citation_coverage`
- `refusal_rate`
- `avg_token_cost_usd`

---

## 6. State Machine Freeze

### 6.1 文档索引状态机

固定主链路：

`not_indexed -> queued -> chunking -> embedding -> ready`

固定异常回路：

- `chunking -> failed`
- `embedding -> failed`
- `ready -> stale -> queued`

### 6.2 问答状态机

固定主链路：

`idle -> retrieving -> synthesizing -> answered`

固定分支：

- `retrieving -> needs_scope`
- `retrieving -> refused`
- `synthesizing -> refused`
- `retrieving -> failed`
- `synthesizing -> failed`

### 6.3 状态语义

- `needs_scope`
  - 现有证据可能足够，但范围过大或候选冲突，需要用户缩小范围
- `refused`
  - 当前 scope 下证据不足，系统必须拒答
- `failed`
  - 系统、队列、provider 或内部异常

`failed` 不得吞并 `refused` 或 `needs_scope`。

---

## 7. Citation / Retrieval Freeze

### 7.1 Citation

`P0` 固定采用：

1. retrieval 先生成 candidate pack
2. answer provider 只能引用 pack 内 chunk
3. 服务端重建 `quote_text` 与 `locator`
4. validator 对越界 citation、缺失 citation、关键 claim 无证据做拒绝

### 7.2 Retrieval

`P0` 固定采用：

- `keyword + semantic` hybrid retrieval
- 单一 `retrieval_mode=hybrid`
- 不在 `P0` 暴露多 rerank 策略切换

---

## 8. 实现顺序约束

进入实现阶段时，主线程顺序固定为：

1. `schema`
2. `shared-types`
3. `API contract`
4. `OpenAPI / SDK`
5. 再拆 `worker / web / test` lane

在第 1-3 步完成前，不启动并行实现 lane。
