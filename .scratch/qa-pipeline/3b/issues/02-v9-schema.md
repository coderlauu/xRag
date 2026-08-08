# 02 — V9 迁移与 3B DDL 约束

**What to build:** V9 新建五张 3B 表与索引，把产品语义落成数据库约束；不写 Repository 逻辑。

**Blocked by:** —
**Status:** complete

## Slice

- `document_chunk_keyword`（chunk_id 主键、GIN 索引、物理删除）。
- `qa_turn_retrieval`、`qa_retrieval_source`、`qa_retrieval_target`、`qa_tool_call`。
- 不修改 V7/V8 任何列，不修改 `qa_turn.status` 的 check，不动 Phase 1 两张表。

## Acceptance

- [x] 3B-DB 相关项：V8→V9 升级建全部表/索引/check，重复执行幂等。
- [x] `unique (turn_id, chunk_id)` 拒绝同一轮重复来源；`uq_qa_source_ordinal` 拒绝重复序号。
- [x] `rerank_applied = (degradation is null)` 拒绝"降级却无原因"与"成功却有原因"。
- [x] 漏斗计数单调不增约束生效。
- [x] `ck_qa_tool_confirm` 双向拦住：无 `confirmed_at` 不得进 RUNNING/SUCCEEDED，
      `PENDING_CONFIRM` 也不得携带确认时间戳；只读工具不需要它。
- [x] `qa_turn.status` 闭集未被修改（直接查 `pg_get_constraintdef` 断言）。

**Evidence:** `Qa3bSchemaMigrationTests`（13 项）；`2026-08-08` 定向执行通过。
另更新 `QaSchemaMigrationTests.QA_TABLES` 登记四张 3B 表——该集合的语义是
"迁移到最新版本后 `qa_*` 应有的全部表"，随分区扩展是预期行为。

**发现一处文档与代码的既有漂移**（属 3A，未改）：`qa_retrieval_action` 实际有
`ck_qa_action_ordinal check (ordinal between 1 and 4)`，动作序号从 1 起；
但 data-model §6.4 的 DDL 片段没列出这条约束。写测试夹具时按文档用了 0，被数据库拒绝。
