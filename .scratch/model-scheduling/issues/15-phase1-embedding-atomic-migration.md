# 15 — Phase 1 Embedding 原子迁移

**What to build:** 在一个始终可编译、可回退的切片中，把 Phase 1 知识库生产调用点迁到工单 14 的能力实现，并保留既有行为。

**Blocked by:** 14

**Status:** ready

## Slice

- 把 `EmbeddingClient` 原子收敛为 `embed(EmbeddingCommand) -> EmbeddingResult`，不保留旧 `embed(List)` / `dimensions()` 或裸字符串 identity 重载。
- 同一提交内完成现有生产/未配置/测试假实现、Routed adapter、Spring Bean wiring、启动维度校验和所有生产调用点迁移。
- 所有知识库调用把 `knowledge_base.embedding_model` 作为显式 `ModelIdentity` key 传入；不引入未冻结 alias，也不做 SQL 猜测式 backfill。
- 保持数量、顺序、维度、批次、429、中断和停机行为；遗留 Ark 凭据不扩展用途。
- 提供回退整张迁移提交或切回旧 Bean 的清晰边界，不保留两套同时活跃的调度路径。

## Acceptance

- [ ] EMB-01、EMB-13~EMB-16 自动化/手工项按矩阵完成。
- [ ] EMB-20~EMB-22 自动化：不同制品不能静默改绑，原子接口替换提交可编译，生产 Spring context 全部注入 routed 实现。
- [ ] 迁移提交任何可观察构建点都不存在接口已变而实现未迁移的编译窗口。
- [ ] 编辑、新增、批量、异步入库调用 Provider 时无活动事务。
- [ ] Phase 1 后端全套测试通过，分块数/向量数/顺序/维度不变量不回归。
- [ ] 本地两个 legacy identity 不互相切换。

**Design:** [architecture §10](../../../tech/model-scheduling/architecture.md#10-embedding-迁移)
