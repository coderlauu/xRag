# 09 — 歧义引导、选择闭环与动作映射

**What to build:** 实现单子问题跨品类歧义、灰区复核、固定引导、结构化选择验证和 3A 声明式动作；不执行检索或生成。

**Blocked by:** 08
**Status:** complete

## Slice

- top≥0.6、两层/三层品类、gap 0.15/0.30 三区间。
- 灰区一次 BALANCED Chat；失败保守引导。
- 保存 2~3 个稳定选项和 assistant guidance；选择/都不是形成下一 turn 输入。
- 映射 KB_TARGETED/GLOBAL_FALLBACK/SYSTEM/NO_MATCH 及稳定原因码。

## Acceptance

- [x] 3A-AMB-01~10、3A-ACT-01~08 自动化通过。
- [x] WAITING_CLARIFICATION 无 action；ACTIONS_READY 无 clarification option。
- [x] SYSTEM+KB 不短路吞问题，低分相近不展示假精确选项。
- [x] 测试断言 Embedding/pgvector/Rerank/StreamingChat 零调用。

## Evidence

- `AmbiguityActionServiceTests` 覆盖单问题跨品类、0.15/0.30 边界、灰区一次 `BALANCED` Chat、非法输出保守
  引导、固定三选项，以及 targeted/fallback/supplement/SYSTEM/NO_MATCH 动作边界。
- `ConversationPersistenceServiceTests` 与 `QaConversationApiIntegrationTests` 覆盖固定 guidance、结构化选择幂等、
  同对话/原选项/最新来源验证和“都不是这些”服务端消息。
- 相关 35 项聚焦测试通过；后端全量 488 项通过，0 failures / 0 errors / 5 skipped。

**Design:** [architecture §9.3~9.4](../../../../tech/qa-pipeline/architecture.md)、[API §3.2](../../../../tech/qa-pipeline/api.md)
