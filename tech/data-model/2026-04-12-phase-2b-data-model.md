# xRag Phase 2B Data Model

**日期：** 2026-04-12
**版本：** `v5 / Phase 2B`
**状态：** draft
**适用范围：** `P0-01 ~ P1-01` 技术方案评估
**对应架构：** [Phase 2B Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-12-phase-2b-architecture.md)
**对应决策：** [Phase 2B P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-11-phase-2b-p0-technical-tradeoffs.md)
**对应前置：** [Phase 2B Contract Freeze Prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-12-phase-2b-contract-freeze-prerequisites.md)

---

## 1. 设计目标

在 `Phase 2A` 已有 schema 基线上，`Phase 2B` 数据模型需要补齐五件事：

- typed scope filters
- follow-up lineage
- claim 级 evidence grouping
- retrieval explain 的正式分类
- `ops` 增强所需的数据来源边界

同时保持：

- `answer_sessions` 仍是单次问答事实源
- `retrieval_runs / retrieval_run_hits` 仍是检索事实源
- `answer_citations` 仍是 citation 事实源

---

## 2. 当前基线约束

当前 schema 已经具备：

- `documents`
- `document_chunks`
- `answer_sessions`
- `retrieval_runs`
- `retrieval_run_hits`
- `answer_citations`
- `document_parse_jobs`
- `document_processing_events`

这意味着 `Phase 2B` 不应：

1. 重新拆出第二套 session 模型
2. 为 explain 再造第二套 trace 表
3. 把 history 演化成 `conversation_threads`

---

## 3. 建模原则

1. 以增量字段和增量表为主，不重命名 `Phase 2A` 既有主对象
2. 让 scope、history、evidence、retrieval 共用一条问答事实链
3. 新增字段优先服务于外部正式 contract，而不是临时 UI 状态
4. `P0` 不为 `ops` 提前引入新的 metrics store
5. 对旧 session 采用向后兼容策略，不强制回填所有新结构

---

## 4. 推荐增量模型

### 4.1 `answer_sessions` 增量字段

推荐新增：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `continued_from_session_id` | `uuid nullable` | 指向上一条被显式继续提问的 session |

说明：

- `continued_from_session_id` 只表示 lineage，不表示“共享消息上下文”。
- 不建议新增 `conversation_id / thread_id`。
- 不建议新增持久化 `scope_summary` 字段，summary 应由 API 层基于 typed payload 生成，避免重复事实源。

推荐索引：

- `(created_at desc)`
- `(continued_from_session_id, created_at desc)`

### 4.2 `answer_sessions.scope_payload` typed shape

`scope_payload` 继续保留 `jsonb`，但语义要从“弱约束对象”升级到“typed payload”。

推荐首版 shape：

| `scope_mode` | payload |
| --- | --- |
| `global` | `{ filters?: ScopeFilterSet | null }` |
| `document` | `{ document_id: string }` |
| `search_result` | `{ document_ids: string[]; truncated: boolean; query?: string | null; filters?: ScopeFilterSet | null }` |

其中 `ScopeFilterSet` 推荐最小字段：

- `tags?: string[]`
- `source_types?: SourceType[]`
- `date_from?: string`
- `date_to?: string`

当前不建议把 `ScopeFilterSet` 单独拆表，原因：

- 这是 session 级快照输入，不是跨 session 复用对象
- 拆表会提前放大 schema 复杂度

### 4.3 新增 `answer_claims`

为支撑 claim grouping，推荐新增：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `id` | `uuid` | 主键 |
| `session_id` | `uuid` | FK -> `answer_sessions.id` |
| `claim_slot` | `varchar(64)` | 与 citation 共享的稳定 claim key |
| `display_order` | `integer` | UI 展示顺序 |
| `claim_text` | `text` | 用户可见 claim 文本 |
| `freshness_badge` | `varchar(32)` | claim 级 freshness 风险提示 |
| `created_at` | `timestamptz` | 创建时间 |

说明：

- `answer_claims` 是 evidence grouping 的正式事实源。
- 这样 `GET /answers/{id}` 可以同时返回：
  - `answer_summary`
  - `citations`
  - `evidence_groups`
- 不需要依赖解析 `answer_summary` 文本来猜 claim 边界。
- `freshness_badge` 作为 claim 级用户可见字段持久化，避免前端临时推断。

推荐唯一约束：

- `(session_id, claim_slot)`

### 4.4 `answer_citations` 的延续用法

`answer_citations` 继续保留当前结构，不建议在 `Phase 2B` 首轮评估时重构为新的引用主表。

冻结方向：

- `claim_slot` 继续作为 claim 与 citation 之间的逻辑关联键
- 每条 citation 仍指向：
  - `session_id`
  - `document_id`
  - `chunk_id`
  - `quote_text`
  - `locator`

不建议在这一轮强行把 `claim_slot` 换成 `claim_id` 外键，原因：

- 这会扩大现有 worker 写入路径与 migration 风险
- 先用 `(session_id, claim_slot)` 已足够表达 group 关系

### 4.5 `retrieval_run_hits.exclusion_reason`

当前列已存在，但还是自由字符串。`Phase 2B` 推荐把它冻结为应用层 enum 语义，DB 列继续保持 `varchar(64)`。

推荐最小分类：

- `deduplicated`
- `rerank_cutoff`
- `answer_budget`
- `low_support`
- `citation_unready`

原因：

- 这些分类直接对应用户可理解的“为什么没有进入答案”
- 又不会把内部 prompt 或模型中间态暴露出来

### 4.6 history 与 recent answers

`Phase 2B` 不建议新增：

- `conversation_threads`
- `chat_messages`
- `answer_history_items`

推荐直接基于 `answer_sessions` 提供 recent list：

- 按 `created_at desc`
- 读取：
  - `question`
  - `status`
  - `scope_mode`
  - `scope_payload`
  - `continued_from_session_id`
  - `created_at`
  - `finished_at`

这能满足“最近问题 / 回看 / continue asking”的最小需求。

### 4.7 `ops` 指标来源

`Phase 2B` 的 `ops` 增强首轮不建议新增专门的 summary table 或 metrics table。

推荐来源：

- `answer_sessions`
- `retrieval_runs`
- `documents`
- `document_parse_jobs`
- 必要时补充 `document_processing_events`

结论：

- `ops` 作为 `P1-01` 可继续复用现有聚合模式
- 不应反向阻塞 `P0` 的 contract freeze

---

## 5. 明确不建议进入主 schema 的内容

`Phase 2B` 当前不建议新增：

- `conversation_threads`
- `chat_messages`
- `scope_filter_snapshots`
- `retrieval_explain_runs`
- `ops_metrics_daily`

理由：

- 这些对象会显著扩大本轮数据面
- 当前产品目标还不足以证明它们是必须事实源

---

## 6. 关键不变量

1. `answer_sessions` 仍然代表一次独立问答，不因 continue asking 而被原地改写。
2. `continued_from_session_id` 只建立 lineage，不引入 memory 语义。
3. `search_result` 的 `document_ids` snapshot 在创建 session 后不可变。
4. `answer_claims` 只应存在于 `answered` session。
5. `answer_citations.claim_slot` 必须能映射到同一 session 下的 claim group。
6. 对旧 session，如果没有 `answer_claims`，API 允许返回空 `evidence_groups`，但 flat citations 仍可读。

---

## 7. 迁移建议

推荐按以下顺序进入后续 freeze：

1. 先新增 `continued_from_session_id`
2. 再新增 `answer_claims`
3. 最后把 `scope_payload`、`exclusion_reason` 与 recent-history 响应一起冻结到 shared types / API

这样可以保证：

- migration 全部是 additive
- 旧 session 不必一次性重写
- 新旧前端可以短期兼容同一套主表
