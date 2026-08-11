# 08b — 把检索接进 turn 处理流程

**What to build:** 让 3A 理解完成后真的执行一次检索，并保证检索失败不改变 3A 的终态。

**Blocked by:** 08
**Status:** complete（`2026-08-11`）

## 为什么会有这张工单

原依赖图里没有它。工单 08 建了 `RetrievalOrchestrator`（编排组件），工单 11 建了响应契约，
但**没有任何工单负责把两者接起来**——`RetrievalOrchestrator` 一直没有业务调用方，真实链路上
`retrieval` 恒为 null。发现于实现工单 11 时（grep 调用方，只有我自己刚加的 controller 注入）。

按 [README 推进规则](../README.md)「发现隐藏依赖先改本图，不把下游契约塞进当前工单」，
它不该被塞进 11 或 13，而应该是 08 与 11 之间的一环，编号 08b。

它挡着三件事：3B 全部手工走查（3B-UI-01~04 都是 `M`）、`RETRIEVAL_FAILED` 错误码的抛出点、
以及工单 12/13 前端的真实验证。

## Slice

- 在 `QuestionUnderstandingService` 的 FINALIZE 之后、`decision.status() == ACTIONS_READY` 时调检索。
- 检索失败不改变 3A 终态。
- 不记阶段（`ck_qa_stage_name` 只允许 3A 的八个阶段名）。

## Acceptance

- [x] 理解完成后 `retrieval` 块存在；库里无内容时是 `NO_RESULT` 而不是 null。
- [x] 检索整体失败时 turn 终态仍是 `ACTIONS_READY` / `ACTIONS_MAPPED`，不是 `FAILED`。
- [x] 短路路径（`WAITING_CLARIFICATION` / `TOO_MANY_TOPICS`）不执行检索。
- [x] E2E 测试不产生真实模型调用。

## 执行记录（`2026-08-11`）

接入点在 `QuestionUnderstandingService.retrieveAfterUnderstanding`。三条设计要点：

1. **它必须自己吞掉所有异常。** 外层那个 catch 会把异常变成 `rejectUnderstanding(FAILED)`，
   而理解**已经成功了**。把检索的失败写成 turn 的 FAILED 会让用户以为"问题没读懂"，实际是
   "库没查成"——后者可重试，前者要改问法（PRD §12.3 / 架构 §19.6）。
2. **不记阶段。** `ck_qa_stage_name` 只允许 3A 的八个阶段名，V9 没有扩展它。3B 的可观测性走
   `qa_turn_retrieval` 的漏斗计数与逐目标状态。要改这条得先改约束，是另一张工单的事。
3. **检索是同步的**，会拉长 `POST /turns` 的响应时间。与 3A 的传输形态一致（api.md §10），
   可取消的流式任务属 3C。

### 接入立刻暴露了工单 08 的一个真 bug

第一次跑 E2E 就拿到 `DuplicateKeyException: uq_qa_target`，随后连接事务 aborted → 503。

根因在 `RetrievalOrchestrator.run`：`plan()` 为每个 (子问题 × 激活通道) 建一个任务，而每个任务
遍历该子问题的**全部** action。ADR 0008 修正后两个向量通道可以同时激活——架构 §9.4 规定
"单个 KB 候选且分数 <0.8"时 3A **同时**产出 `KB_TARGETED` 与 `GLOBAL_FALLBACK`——于是同一条
action 被两个通道各记一次 target。

**这个组合只有真实链路才产生**（3A 得真的产出那对动作），所以工单 08 阶段的 17 条用例
一条都没覆盖到它，包括我自己补的 CFG-05（它用了多个 `KB_TARGETED`，恰好都是同一类动作）。

修法：目标归属于**执行该动作的那个通道**——`KB_TARGETED` 归定向向量通道、`GLOBAL_FALLBACK`
归全局向量通道、关键词通道不产生目标。新增 `producesTarget(channelType, actionType)` 表达这条，
并补了一条正面复现的用例。

### 另一个被接入暴露的问题：E2E 在真实计费

`QuestionUnderstandingE2ETests` / `QaResilienceE2ETests` / `QaPrivacyAuditIntegrationTests` 都没有
导入 `FakeEmbeddingConfig`（它们不测 embedding）。接入之后，走到 `ACTIONS_READY` 的用例会执行
检索、检索要算查询向量——于是**跑一次测试就打一次真实的阿里云 Embedding 接口**，`backend/.env`
里的 Key 让它真的成功了。

三个类都补上 `FakeEmbeddingConfig`。证据很直接：`QuestionUnderstandingE2ETests` 的耗时从
**6.9 秒降到 1.3 秒**。

注意这与"关掉入库派发器"是两件事：那条属性只管入库派发器，管不到检索——检索是同步的，
就在请求线程里跑。

## 遗留

- `RETRIEVAL_FAILED`（502）仍然没有抛出点。接入之后检索失败会落成
  `qa_turn_retrieval.status = RETRIEVAL_FAILED`（200 + 状态），而契约 §12 把它列为 502。
  这两者哪个对，取决于工具失败那条同源的裁决（契约 §10 与 §12 的冲突，见工单 11 的遗留）。
  **没有为它硬造抛出点。**
- 3B 手工走查现在**可以做了**，但需要先往库里放真实内容（知识库里得有东西才召得回来）。
  属 Step E（工单 15）的准备工作。
