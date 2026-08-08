# 04 — 意图整树 revision Repository 与领域约束

**What to build:** 实现 ADR 0007 的不可变树快照、expected revision 命令、完整图校验和 JVM 原子缓存；不提供 HTTP API 或评分。

**Blocked by:** 02
**Status:** complete

## Slice

- 固定虚拟根、GROUP/KB/SYSTEM、稳定 UUID 邻接树和 1~3 层约束。
- `FOR UPDATE` 锁 head，构造完整新快照，单事务插 revision+nodes+推进 head。
- 创建/更新/启停/删除子树领域命令及影响统计。
- KnowledgeBaseLookup 校验绑定；成功 commit 后 AtomicReference 替换，故障清空重载。

## Acceptance

- [x] 3A-TREE-01~16 自动化通过。
- [x] 两个同 expected revision 命令只一个成功，失败者无任何新 revision/node 行。
- [x] 旧 snapshot 在并发发布后内容不变；新 snapshot 不出现孤儿/环/混合版本。
- [x] 不提供 mutable node update/delete SQL 旁路或 MCP 类型。

## Evidence

- `IntentTreeStoreTests` 以隔离 PostgreSQL schema 覆盖空 head、虚拟根、稳定 UUID、结构/字段/知识库约束、
  三层上限、子树启停删除、expected revision 并发、旧快照不变、缓存故障重载和影响预览。
- 所有写命令均锁 singleton head、复制完整快照、整图校验后一次推进 revision；公开接口不提供 parent/type/
  enabled 的单行可变旁路，节点闭集仍为 `GROUP/KB/SYSTEM`。
- `./mvnw test`：453 tests，0 failures，0 errors，5 skipped（真实收费 API 按预期跳过）。

**Design:** [ADR 0007](../../../../docs/adr/0007-versioned-intent-tree.md)、[data model §8~9](../../../../tech/qa-pipeline/data-model.md)
