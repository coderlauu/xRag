# 08 — 检索编排、部分失败与重试

**What to build:** 把通道、融合、精排串成一次检索，落库来源与目标状态，并实现只重跑失败目标的重试。

**Blocked by:** 05, 06, 07
**Status:** complete（`2026-08-11`）

## Slice

- 按 (子问题 × 通道) 提交有界并行任务，并行度受配置约束。
- 每个目标独立记录 `SUCCEEDED/FAILED` 与归一化原因；`partial` 由状态算出。
- `NO_RESULT` 与 `RETRIEVAL_FAILED` 是两个不同状态。
- 重试只重跑失败目标，不新建 turn、不重新评分、不重问歧义。
- 漏斗三个计数落 `qa_turn_retrieval`。

## Acceptance

- [x] 3B-FAIL-01~09、3B-CFG-05 自动化通过。`RetrievalOrchestratorIntegrationTests` 16 条全绿；
      矩阵状态列的 `passed` 回写留给 Step E（工单 15），本工单只保证自动化存在且通过。
- [x] `partial` 不由来源条数推断（构造"条数少但全部成功"的用例反证）。
- [x] 重试后理解结果字段（子问题、意图、动作、歧义选择）逐字段不变。
- [x] 并发峰值下无连接获取超时。3 子问题 × 2 库 = 6 个任务，超过 `maxParallelism` 默认的 4。
- [x] 模型调用与检索均在事务外。检索侧由 `IN_TRANSACTION` 观测点正面断言；
      精排调用点在 `run()` 里、事务只覆盖 `save`/`merge`，**尚无观测点断言**，见下。

## 执行记录（`2026-08-11`）

在上一会话（`7032f87a`，08-10 因 403 中断）写好的主干上收尾，发现并修了两个真 bug：

| # | 缺陷 | 违反 | 反证 |
|---|---|---|---|
| 1 | 重放是**替换**而非合并：`save` 按 `turn_id` 清台，而 `retryFailed` 只带回失败那批结果，上一次成功目标的来源与目标行被删掉 | 架构 §19.7「重放结果合并进同一 turn 的来源集合」 | 撤回修复后 `fail07` 报 `EmptyResultDataAccess`（成功目标的行确实没了） |
| 2 | 重放后 `status`/`partial` 用**本批**目标集算：本批全失败 → 整轮判 `RETRIEVAL_FAILED`，尽管上次有成功目标 | §19.6 里 `RETRIEVAL_FAILED` 的定义是"全部目标失败" | 撤回修复后「重试再次失败时不把整轮判成全部失败」断言失败 |

两个 bug 在原有测试下都不可见：`fail08` 只造了**一个**动作，单目标场景下"清台重插"与"合并"结果完全一样。
撤回修复后 `fail08` 依旧绿，印证了这一点——这正是 FAIL-01/07 未自动化的代价。

`fail03` 原先名实不符（名字说 `RETRIEVAL_FAILED`，测试体断言 `NO_RESULT`，注释里写了"改为断言相邻语义"），已改为真正制造全目标失败。

修法：`RetrievalResultRepository.merge` 只删本批 `action_id` 的行、按 `max(ordinal)+1` 续号、
跳过已被保留来源占用的 chunk（`uq_qa_source_chunk`），并基于**库里的全量目标**重算 turn 级状态。

**规格空白处的判断**（§19.7 只规定了来源合并与 `partial` 重算，没说漏斗与降级）：`after_rerank` 取库里
全量来源条数；`channel_hits` / `after_dedup` 累加本次重放的检索工作量；`rerank_applied` / `degradation`
用最近一次精排的实际结果。已在 `merge` 的 javadoc 里标注，日后 §19.7 补全则以文档为准。

## 遗留

- **精排在事务外**这一条只有代码结构保证，没有观测点断言。检索侧的 `IN_TRANSACTION` 是靠
  `ChunkSearchPort` 装饰器拿到的，精排要同样的断言得再包一层 `RerankClient`，属于工单 07 的范围。
- 全量回归里 `IngestionIntegrationTests` / `ScheduledSyncIntegrationTests` 的 `awaitStatus` 偶发超时，
  落点每次漂移。**根因是测试上下文数量耗尽数据库连接**，不是业务代码：

  | 证据 | 数值 |
  |---|---|
  | 测试期间 Postgres 连接峰值 | **88 / 100**（`max_connections=100`） |
  | 每个上下文变体的 Hikari 池 | 10（`DATABASE_MAX_POOL_SIZE` 默认值） |
  | 现存上下文变体 | 10 余个（注解组合不同就分裂：`@AutoConfigureMockMvc`、`@Import`、`@TestPropertySource`、嵌套 `@TestConfiguration`） |

  验证方式是反向的：给本类单独加一条 `@TestPropertySource`（内容只是关掉入库派发器，看起来只会
  更安全）会让它从"与另外三个类共用一个上下文"变成"独占一个新上下文"，连接需求 +10，全量回归的
  超时数从 1 个涨到 **3 个**；撤回后 613 tests 全绿。**不是本工单引入**（HEAD 基线连上下文都起不来，
  没有可比性），修法是压低池大小或合并上下文变体，属于跨模块的测试基础设施，不塞进本工单。
  ADR 0002 的单实例假设在这里表现为"多个 `IngestionDispatcher` 并存"，但它不是超时的主因——
  抢任务会让任务被别的上下文执行完，不会让状态 30 秒不变。
