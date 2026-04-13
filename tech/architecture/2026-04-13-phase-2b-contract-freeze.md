# xRag Phase 2B Contract Freeze

**日期：** 2026-04-13
**版本：** `v5 / Phase 2B`
**状态：** freeze-ready
**适用范围：** `P0-01 ~ P0-04`
**对应文档：**
- [Phase 2B Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-12-phase-2b-architecture.md)
- [Phase 2B Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-12-phase-2b-data-model.md)
- [Phase 2B API Design](/Users/coderlauu/xRag/tech/api/2026-04-12-phase-2b-api.md)
- [Phase 2B P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-11-phase-2b-p0-technical-tradeoffs.md)
- [Phase 2B Contract Freeze Prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-12-phase-2b-contract-freeze-prerequisites.md)
- [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)
- [Phase 2A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md)

---

## 1. 文档目的

这份文档把 `Phase 2B / P0` 进入实现前最需要稳定的五类 contract 固定下来：

1. `schema`
2. `shared-types`
3. `API contract`
4. `状态机 / scope / history`
5. `citation / retrieval / freshness / eval contract`

后续编码若与本文冲突，以本文为准；如需偏离，必须先回写文档，而不是在实现中临时改语义。

---

## 2. Source Of Truth 顺序

`Phase 2B / P0` 的 contract source-of-truth 顺序固定为：

1. [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)
2. [Phase 2B Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-13-phase-2b-contract-freeze.md)
3. [Phase 2B Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-12-phase-2b-data-model.md)
4. [Phase 2B API Design](/Users/coderlauu/xRag/tech/api/2026-04-12-phase-2b-api.md)
5. `apps/api/src/database/schema.ts`
6. `packages/shared-types/src/index.ts`
7. OpenAPI / SDK / 实现代码

---

## 3. Schema Freeze

### 3.1 保持稳定的 enum

以下 enum 名称和值在 `Phase 2B / P0` 保持稳定，不重命名、不改语义：

| enum | 值 |
| --- | --- |
| `source_type` | `text`, `file`, `pdf`, `link` |
| `answer_scope_mode` | `global`, `search_result`, `document` |
| `retrieval_mode` | `hybrid` |
| `answer_session_status` | `idle`, `retrieving`, `synthesizing`, `answered`, `needs_scope`, `refused`, `failed` |

冻结结论：

1. `Phase 2B` 不新增新的 `scope_mode`。
2. `Phase 2B` 不新增新的 answer status。
3. `retrieval_mode` 继续固定为 `hybrid`。

### 3.2 `answer_sessions` 增量字段

`answer_sessions` 在 `P0` 必须新增：

- `continued_from_session_id`

冻结结论：

1. 类型固定为 `uuid nullable`。
2. 语义固定为“显式 lineage”，不表示 memory thread。
3. 推荐外键：
   - `references answer_sessions(id) on delete set null`
4. `scope_mode` 与 `scope_payload` 继续留在 `answer_sessions` 主表，不拆新表。

### 3.3 `answer_sessions.scope_payload` typed shape

`scope_payload` 在 `P0` 固定采用以下形状：

| `scope_mode` | payload |
| --- | --- |
| `global` | `{ filters?: ScopeFilterSet | null }` |
| `document` | `{ document_id: string }` |
| `search_result` | `{ document_ids: string[]; truncated: boolean; query?: string | null; filters?: ScopeFilterSet | null }` |

其中 `ScopeFilterSet` 固定为：

- `tags?: string[]`
- `source_types?: SourceType[]`
- `date_from?: string`
- `date_to?: string`

固定约束：

1. `tags`
   - 最多 `20` 个
   - 使用用户可见 tag name，不使用 tag id
2. `source_types`
   - 只允许 `text | file | pdf | link`
   - 最多 `4` 个，且去重
3. `date_from / date_to`
   - 使用 `ISO8601` 字符串
   - 按当前 repo 既有语义过滤 `documents.imported_at`
   - `date_from` 为包含下界，`date_to` 为包含上界
4. `document`
   - 只允许 `document_id`
   - 不附带额外 filters
5. `search_result`
   - `document_ids` 上限固定为 `100`
   - `truncated` 必填
   - `document_ids` snapshot 创建后不可变

### 3.4 新增 `answer_claims`

`P0` 固定新增以下表：

1. `answer_claims`

固定字段：

- `id`
- `session_id`
- `claim_slot`
- `display_order`
- `claim_text`
- `freshness_badge`
- `created_at`

冻结结论：

1. `session_id` -> `answer_sessions.id` 使用 `on delete cascade`
2. `(session_id, claim_slot)` 必须唯一
3. `freshness_badge` 固定采用应用层稳定分类：
   - `ready`
   - `stale_risk`
   - `unknown`
4. `answer_claims` 只承载已回答结果中的结构化 claim，不承载 prompt 或 chain-of-thought

推荐索引：

- `(session_id, display_order)`
- `(session_id, claim_slot)`

### 3.5 `answer_citations` 延续语义

以下字段语义在 `P0` 固定：

- `answer_citations.claim_slot`
  - 继续作为 claim group 的稳定关联键
- `answer_citations.quote_text`
  - 继续由服务端重建，不信任模型自由输出
- `answer_citations.locator`
  - 继续作为 document/detail 回跳定位信息

冻结结论：

1. `Phase 2B` 不把 `claim_slot` 重构为 `claim_id` 外键。
2. cited document 继续要求 `index_status=ready` 且 `citation_ready=true`。
3. `stale / failed` 文档不得成为引用证据。

### 3.6 `retrieval_run_hits.exclusion_reason`

`retrieval_run_hits.exclusion_reason` 继续保留 `varchar(64)`，但在 `P0` 固定应用层分类：

- `deduplicated`
- `rerank_cutoff`
- `answer_budget`
- `low_support`
- `citation_unready`

冻结结论：

1. 当前不新增数据库 enum。
2. 这些值同时构成前端和 OpenAPI 可见的正式 contract。
3. 不再允许面向用户暴露任意自由字符串。

### 3.7 推荐索引

`P0` 推荐新增：

- `idx_answer_sessions_continued_from_session_id`

继续复用：

- `idx_answer_sessions_created_at`
- `idx_retrieval_runs_session_id`
- `idx_retrieval_run_hits_retrieval_run_id`
- `idx_answer_citations_session_id`

### 3.8 明确不进入主 schema

以下内容在 `P0` 明确不进入主 schema：

- `conversation_threads`
- `chat_messages`
- `scope_filter_snapshots`
- `retrieval_explain_runs`
- `ops_metrics_daily`
- `evaluation_cases`
- `evaluation_runs`

---

## 4. Shared Types Freeze

### 4.1 新增导出类型

`packages/shared-types` 在 `P0` 必须新增并稳定导出：

- `ScopeFilterSet`
- `GlobalAnswerScope`
- `DocumentAnswerScope`
- `SearchResultAnswerScope`
- `RetrievalExclusionReason`
- `AnswerClaimFreshnessBadge`
- `AnswerEvidenceGroup`
- `AnswerRetrievalTraceSummary`
- `AnswerHistoryListItem`
- `ListAnswerSessionsQuery`
- `ListAnswerSessionsResponse`

### 4.2 现有类型的增量字段

以下现有类型在 `P0` 需要增量字段：

`CreateAnswerRequest`
- `continued_from_session_id?: string`

`AnswerSessionResponse`
- `scope_summary`
- `continued_from_session_id`
- `updated_at`
- `evidence_groups`

`AnswerRetrievalTraceItem`
- `exclusion_reason` 改为 `RetrievalExclusionReason | null`

`AnswerRetrievalTraceResponse`
- `summary`

### 4.3 稳定 shape

以下 shape 在 `P0` 固定：

`AnswerEvidenceGroup`
- `claim_slot`
- `claim_text`
- `freshness_badge`
- `citations`

`AnswerRetrievalTraceSummary`
- `query_normalized`
- `eligible_document_count`
- `lexical_hit_count`
- `semantic_hit_count`
- `merged_hit_count`
- `rerank_strategy`
- `latency_ms`

`AnswerHistoryListItem`
- `session_id`
- `question`
- `status`
- `scope`
- `scope_summary`
- `continued_from_session_id`
- `updated_at`

### 4.4 命名规则

shared types 继续遵守当前 repo 约定：

1. TypeScript 类型使用 `PascalCase`
2. API JSON 字段使用 `snake_case`
3. enum 值使用稳定英文小写下划线
4. 列表资源继续采用 `page / page_size / total`

---

## 5. API Contract Freeze

### 5.1 保持不变

- 基础前缀仍为 `/api/v1`
- `GET /documents` 继续是 keyword search 基线
- `GET /documents?q=...` 不引入问答语义
- `GET /documents/{documentId}`、`/evidence`、`/reindex` 继续保留原路径

### 5.2 `GET /api/v1/answers`

`P0` 固定新增：

- `GET /api/v1/answers`

推荐查询参数：

- `page`
- `page_size`

固定结论：

1. 分页模式采用当前 repo 既有的 `page / page_size / total`。
2. `page_size` 上限固定为 `100`。
3. 当前不新增 cursor-based 分页。
4. 当前不新增复杂 filter query；recent history 首版只做最近问题列表。

### 5.3 `POST /api/v1/answers`

保持路径不变，固定结论：

1. 继续使用 `202 Accepted`。
2. `question` 长度上限继续固定为 `2000`。
3. `scope` 在 `P0` 继续为必填，即使 `continued_from_session_id` 存在也不例外。
4. `continued_from_session_id` 为可选 lineage 字段，不自动继承隐藏上下文。
5. API 不新增 `/follow-up` 或 `/continue` 子路径。

### 5.4 `GET /api/v1/answers/{sessionId}`

保持路径不变，固定新增以下响应字段：

- `scope_summary`
- `continued_from_session_id`
- `updated_at`
- `evidence_groups`

兼容结论：

1. 现有 flat `citations` 继续保留。
2. `evidence_groups` 是 additive，不替换旧字段。
3. 对旧 session，如果尚无 claim 数据，允许返回空数组 `[]`。

### 5.5 `GET /api/v1/answers/{sessionId}/retrieval`

保持路径不变，固定新增：

- `summary`

`summary` 为：

```json
{
  "query_normalized": "query",
  "eligible_document_count": 12,
  "lexical_hit_count": 8,
  "semantic_hit_count": 10,
  "merged_hit_count": 13,
  "rerank_strategy": "hybrid",
  "latency_ms": 126
}
```

固定结论：

1. retrieval workbench 继续复用同一资源，不新开 explain endpoint。
2. `exclusion_reason` 只允许使用本文固定的最小分类。
3. URL state 继续由 web 维护，不进入 API contract。

### 5.6 `ops` 资源

`Phase 2B / P0` 不新增新的 `/ops` 一级资源。

冻结结论：

1. `P1-01` 若进入实现，只能在现有 `/ops/*` 资源族上扩展。
2. `ops` 指标口径必须继续与 [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md) 同源。
3. `ops` 属于 freeze-late，不阻塞 `P0` 实现主链。

---

## 6. State Machine / Answer Quality Freeze

### 6.1 问答状态机

`Phase 2B` 固定沿用 `Phase 2A` 状态机：

主链路：

`idle -> retrieving -> synthesizing -> answered`

固定分支：

- `retrieving -> needs_scope`
- `retrieving -> refused`
- `synthesizing -> refused`
- `retrieving -> failed`
- `synthesizing -> failed`

### 6.2 状态语义

- `needs_scope`
  - 当前 evidence 可能足够，但范围过大或冲突明显，需要用户显式缩小范围
- `refused`
  - 当前显式 scope 下证据不足，系统必须拒答
- `failed`
  - 系统、队列、provider 或内部异常

`failed` 不得吞并 `refused` 或 `needs_scope`。

### 6.3 Scope / History Freeze

`P0` 固定采用以下规则：

1. `continue asking` 必须创建新的 `session_id`
2. 旧 session 保持只读，不得原地改写
3. `continued_from_session_id` 只记录 lineage，不记录 memory
4. `scope` 继续要求显式传入
5. `search_result` snapshot 一旦创建，不得在后续读取时重算
6. filters 只支持 inclusion 型过滤
   - 不支持排除型条件
   - 不支持嵌套布尔组合器
   - 不支持隐式自动扩 scope

### 6.4 Citation / Evidence Freeze

`P0` 固定采用：

1. answer provider 只能引用 candidate pack 内 chunk
2. 服务端继续重建 `quote_text` 与 `locator`
3. `answer_claims` + `answer_citations.claim_slot` 构成 evidence group 的唯一事实链
4. 每个 evidence group 都必须能回到 citation
5. 不暴露 prompt、chain-of-thought 或自由文本推理轨迹

### 6.5 Freshness / Eval Freeze

`P0` 固定采用：

1. `stale / failed` 文档不得进入 citation
2. `freshness_badge` 必须在 claim/evidence 层可见
3. answer 行为变化不得绕过既有 `citation / refusal / freshness / release-readiness` gate
4. 当前不新增新的 evaluation runtime API

---

## 7. 实现顺序约束

进入实现阶段时，主线程顺序固定为：

1. `schema + migration`
2. `shared-types`
3. `API DTO / OpenAPI / SDK`
4. `answer repository / service / worker orchestration`
5. 再拆 `web / test` lane

在第 1-3 步完成前，不启动并行实现 lane。
