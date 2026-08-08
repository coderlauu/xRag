# 10 — 3A 理解编排与稳定终态

**What to build:** 把持久化、历史、摘要、改写、固定 revision、并行评分、歧义/动作和终态聚合为一个同步应用服务，形成真实 3A 垂直链路。

**Blocked by:** 03, 05, 06, 07, 08, 09
**Status:** complete

## Slice

- 唯一 `QuestionUnderstandingService.process(turnId)`，严格按架构阶段执行。
- 每个外部调用在事务外；阶段结果汇总后一次终态事务持久化。
- 区分增强降级、单问题无匹配、整轮失败和 too-many-topics。
- API TurnProcessor 切到真实实现；GET turn 可恢复相同 Inspector。

## Acceptance

- [x] 3A-E2E-01~05、3A-API-02、06 自动化通过。
- [x] 同一 turn 不重复进入 PROCESSING；终态 CAS 二选一且不可覆盖。
- [x] turn/match/option/action 全部引用同一 intent revision。
- [x] 不生成最终答案、来源、SSE、taskId 或许可状态。

## Evidence

- `QuestionUnderstandingServiceTests` 覆盖固定 revision、阶段顺序、PROCESSING CAS、失败与主题过多稳定终态。
- `QuestionUnderstandingE2ETests` 通过真实 HTTP 覆盖动作、歧义选择、组合降级、主题过多、刷新恢复与幂等零重复调用。
- `QaConversationApiIntegrationTests` 覆盖稳定 Inspector 恢复及 Prompt、摘要正文、Provider/调用标识隐私边界。
- `2026-08-08`: 后端全量 496 tests，0 failure / 0 error，5 skipped。

**Design:** [architecture §4](../../../../tech/qa-pipeline/architecture.md)、[API §3](../../../../tech/qa-pipeline/api.md)
