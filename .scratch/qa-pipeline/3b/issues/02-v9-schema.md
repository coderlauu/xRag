# 02 — V9 迁移与 3B DDL 约束

**What to build:** V9 新建五张 3B 表与索引，把产品语义落成数据库约束；不写 Repository 逻辑。

**Blocked by:** —
**Status:** blocked（Step C 待确认）

## Slice

- `document_chunk_keyword`（chunk_id 主键、GIN 索引、物理删除）。
- `qa_turn_retrieval`、`qa_retrieval_source`、`qa_retrieval_target`、`qa_tool_call`。
- 不修改 V7/V8 任何列，不修改 `qa_turn.status` 的 check，不动 Phase 1 两张表。

## Acceptance

- [ ] 3B-DB 相关项：空库 Flyway 升级建全部表/索引/check，旧迁移仍可重复验证。
- [ ] `unique (turn_id, chunk_id)` 拒绝同一轮重复来源。
- [ ] `rerank_applied = (degradation is null)` 拒绝"降级却无原因"与"成功却有原因"。
- [ ] 漏斗计数单调不增约束生效。
- [ ] `ck_qa_tool_confirm` 拒绝"无 confirmed_at 却处于 RUNNING/SUCCEEDED/FAILED"的有副作用工具行。
- [ ] `qa_turn.status` 闭集**未被修改**（回归断言）。
