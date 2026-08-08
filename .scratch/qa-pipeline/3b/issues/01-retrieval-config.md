# 01 — 3B 配置对象与启动校验

**What to build:** `QaRetrievalProperties` / `QaToolProperties` 两个配置对象与全部启动期交叉校验；不实现检索或工具逻辑。

**Blocked by:** —
**Status:** blocked（Step C 待确认）

## Slice

- 显式配置 `spring.datasource.hikari.maximum-pool-size`（基线 20），不再落到默认 10。
- 绑定 `max-parallelism=4`、`channel-top-k=50`、`rerank-input-size=30`、`top-n=8`、`rrf-k=60`。
- 工具白名单绑定：`name` / `display-name` / `description` / `side-effect` / `timeout` / `input-schema`。
- 定义检索专用有界执行器 Bean，与意图评分执行器**不共享队列**。
- 全部交叉校验在启动期完成，失败即拒绝启动并打印两个实际值。

## Acceptance

- [ ] 3B-CFG-01~04、3B-CFG-06 自动化通过。
- [ ] `max-parallelism >= maximum-pool-size` 启动失败，错误同时含两个实际值与配置路径。
- [ ] `rerank-input-size > RerankProfile.maxDocuments`、`channel-top-k < top-n` 均启动失败。
- [ ] 工具 `name` 重复启动失败，错误含两处配置路径。
- [ ] 本工单不新增表、controller 或模型调用。
