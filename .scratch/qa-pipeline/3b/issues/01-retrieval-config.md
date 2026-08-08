# 01 — 3B 配置对象与启动校验

**What to build:** `QaRetrievalProperties` / `QaToolProperties` 两个配置对象与全部启动期交叉校验；不实现检索或工具逻辑。

**Blocked by:** —
**Status:** complete

## Slice

- 显式声明 `spring.datasource.hikari.maximum-pool-size`=10，不再依赖隐式默认。
- 绑定 `max-parallelism=4`、`channel-top-k=50`、`rerank-input-size=30`、`top-n=8`、`rrf-k=60`。
- 工具白名单绑定：`name` / `display-name` / `description` / `side-effect` / `timeout` / `input-schema`。
- 定义检索专用有界执行器 Bean，与意图评分执行器**不共享队列**。
- 全部交叉校验在启动期完成，失败即拒绝启动并打印两个实际值。

## Acceptance

- [x] 3B-CFG-01~04、3B-CFG-06 自动化通过。
- [x] `max-parallelism >= maximum-pool-size` 启动失败，错误同时含两个实际值与配置路径。
- [x] `rerank-input-size > RerankProfile.maxDocuments`、`channel-top-k < top-n` 均启动失败。
- [x] 工具 `name` 重复启动失败，错误含两处配置路径。
- [x] 本工单不新增表、controller 或模型调用。

**Evidence:** `QaRetrievalConfigurationTests`（8 项）、`QaToolPropertiesTests`（6 项）；
`2026-08-08` 定向执行通过。

**实测推翻了一个起草期的取值**：池大小原定 20，实跑发现每个 Spring 测试上下文各建一个池，
一次 `verify` 十几个上下文乘起来打穿 Postgres 默认 `max_connections=100`，成片
`FATAL: sorry, too many clients already`。改为显式声明 **10**（= 原隐式默认值），
行为不变但多了启动校验；ADR 0008 决策 5、架构 §19.3/§22.1 与本工单同步更正。

**踩过一次的坑**：曾试图用 `src/test/resources/application.properties` 只覆盖池大小——
同名文件会**整体覆盖**主配置（测试资源在 classpath 更前面），把 `spring.config.import`
的 `ai-models.yml` 一起弄丢，症状是 `app.ai.policy-version: must not be blank` 导致
所有 `@SpringBootTest` 上下文启动失败。该文件已删除，不要再走这条路。
