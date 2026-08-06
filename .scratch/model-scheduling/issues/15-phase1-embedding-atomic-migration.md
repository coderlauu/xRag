# 15 — Phase 1 Embedding 原子迁移

**What to build:** 在一个始终可编译、可回退的切片中，把 Phase 1 知识库生产调用点迁到工单 14 的能力实现，并保留既有行为。

**Blocked by:** 14

**Status:** done

## Slice

- 把 `EmbeddingClient` 原子收敛为 `embed(EmbeddingCommand) -> EmbeddingResult`，不保留旧 `embed(List)` / `dimensions()` 或裸字符串 identity 重载。
- 同一提交内完成现有生产/未配置/测试假实现、Routed adapter、Spring Bean wiring、启动维度校验和所有生产调用点迁移。
- 所有知识库调用把 `knowledge_base.embedding_model` 作为显式 `ModelIdentity` key 传入；不引入未冻结 alias，也不做 SQL 猜测式 backfill。
- 保持数量、顺序、维度、批次、429、中断和停机行为；遗留 Ark 凭据不扩展用途。
- 提供回退整张迁移提交或切回旧 Bean 的清晰边界，不保留两套同时活跃的调度路径。

## Acceptance

- [x] EMB-01：`com.app.knowledge.embedding.EmbeddingClient` 已删除，唯一入口是 `com.app.ai.model.EmbeddingClient.embed(EmbeddingCommand)`，不存在旧方法或裸 String 重载。
- [x] EMB-13：入库、文档启用、分块编辑/新增/批量启用四条路径都显式传 `knowledge_base.embedding_model`。
- [x] EMB-14：Provider 调用仍在事务外，`FakeEmbeddingConfig.IN_TRANSACTION_AT_CALL` 断言不变且全绿。
- [x] EMB-15：Phase 1 全套知识库测试通过，分块数/向量数/顺序/维度不变量未回归。
- [x] EMB-16：两个 legacy identity 各自绑定唯一 deployment，不互相切换（catalog 侧由 `cfg16` 守，调用侧由工单 14 的 `emb19` 守）。
- [x] EMB-20：`EmbeddingConfig` 启动校验已有知识库的身份必须存在于 catalog 且维度一致，否则启动失败并要求显式全量重建。
- [x] 迁移提交任何可观察构建点都不存在接口已变而实现未迁移的编译窗口（一个提交内完成）。
- [x] `./mvnw -B verify` 通过（347 tests，0 failures，2 skipped）。
- [ ] EMB-21、EMB-22 的**自动化**用例待补：原子替换的编译完整性与"生产 context 全部注入 routed 实现"目前只由本次人工验证与 `ApplicationTests` 间接覆盖。

**Design:** [architecture §10](../../../tech/model-scheduling/architecture.md#10-embedding-迁移)

## Notes

- 分三个提交落地：`10574a3` Provider 适配器（纯新增）、`2918ac7` Spring 装配（纯新增，
  bean 名先取 `routedEmbeddingClient` 以避开 `BeanDefinitionOverrideException`）、
  `5aa33f4` 原子替换。前两步可独立编译通过，真正不可分割的只有第三步。
- **出站限流的主动节流放在 Provider 适配器**（沿用 `app.embedding.batch-interval`），
  作用域从 Phase 1 的"单次 embed 调用内"提升到"Provider 实例内"。理由是那次事故的根因是
  账号级限流，而并发入库任务在旧实现下各自计时、本来就防不住；代价是并发入库会排在同一道
  闸后面。
- `app.embedding.*` 只保留 `batch-interval`。`max-retries` / `retry-base-delay` 一并删除——
  重试已完全由 `FailureActionPolicy` + `AttemptBudget` 接管，留着就是与调度层矛盾的死配置。
- **新增配置项 `app.knowledge.default-embedding-identity`**（计划外）。建库时要写
  `embedding_model`，原取自 `app.embedding.model`；catalog 里两个 Embedding 身份都没标
  `default-candidate`，按路由排序会选中 `text-embedding-v3`，而现状默认是
  `doubao-embedding-vision`。静默换掉新建知识库绑定的模型不可接受，故改为显式属性并在启动时
  对照 catalog 校验存在性，默认值与现状一致。
- 未配置密钥时不再有 `UnconfiguredEmbeddingClient`：Provider 未配置会被 `RoutePlanner` 过滤，
  调用时以 `NO_ELIGIBLE_MODEL` 失败，`ApiExceptionHandler` 保留"该设哪个环境变量"的提示。
  "依赖缺失不拦启动"的行为不变。
