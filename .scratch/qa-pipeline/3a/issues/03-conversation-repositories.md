# 03 — 对话、消息、本轮 Repository 与幂等

**What to build:** 实现 JdbcTemplate Repository 和短事务聚合，保证首问、消息序号、单活动本轮、幂等和逻辑删除；不调用模型。

**Blocked by:** 02
**Status:** complete

## Slice

- 原子创建/读取 conversation + user message + SAVED turn。
- 用数据库唯一性和正文 hash 处理同 key 重放/冲突。
- 显式 RowMapper、cursor 查询、消息 sequence、title/version CAS。
- 终态 CAS、处理结果聚合写入和对话逻辑删除传播。

## Acceptance

- [x] 3A-DB-03~11、3A-CONV-01~06、09~12 自动化通过。
- [x] 同一 key 竞争只产生一套业务行；不同正文不会误复用。
- [x] Repository 普通方法不能看见逻辑删除数据，历史方法命名显式。
- [x] 所有事务内只有 PostgreSQL 操作，无 Chat/HTTP。

## Evidence

- `ConversationPersistenceServiceTests` 以隔离 PostgreSQL schema 覆盖首问事务、同 key 竞争、单活动 turn、
  终态 CAS、列表/消息游标、标题 version CAS、逻辑删除传播、摘要 revision/水位 CAS，以及
  `WAITING_CLARIFICATION` / `ACTIONS_READY` 结果互斥聚合。
- Repository 聚合只依赖 `JdbcTemplate` 与 `PlatformTransactionManager`，没有 Chat/HTTP 依赖。
- `./mvnw test`：453 tests，0 failures，0 errors，5 skipped（真实收费 API 按预期跳过）。

**Design:** [architecture §5](../../../../tech/qa-pipeline/architecture.md)、[data model §4~7](../../../../tech/qa-pipeline/data-model.md)
