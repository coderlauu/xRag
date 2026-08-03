# 07 — 确定性 RoutePlan

**What to build:** 从不可变 catalog 和调用约束生成包含全部过滤原因的不可变 RoutePlan；不执行 Provider 调用。

**Blocked by:** 无（01 已于 2026-08-02 完成）

**Status:** done（2026-08-03）

## Slice

- 硬过滤 capability、feature、tier、地域、caller allow-list、配置和 identity；Chat 的 `TOOL_CALLING` 由 tools/历史消息结构推导，不读公共布尔开关；通过不依赖 Resilience4j 的只读 breaker 状态快照解释调用开始时的 OPEN。
- 排序固定为 default → priority → deployment ID。
- 记录所有候选的 `ELIGIBLE/FILTERED` 与稳定原因，不读取执行期 breaker permission。
- RoutePlan 固定输入快照；生成后配置对象变化不影响顺序。
- 为 Embedding 计划能在等价 deployment 限制不同时生成安全 work-unit/batch 约束。

## Acceptance

- [x] RTE-01~RTE-10、RTE-12、RTE-13 自动化；RTE-06 覆盖三种工具能力推导入口，RTE-10 只消费传入的 breaker 状态快照，不申请运行时许可。
- [x] RTE-17 自动化：按所有等价 Embedding 候选的最小 batch limit 固定 work-unit 范围。
- [x] 相同输入和快照重复规划 100 次顺序一致。
- [x] 同 identity 候选的批量上限不会让备选 deployment 收到超限 batch。
- [x] 所有过滤 deployment 都保留在可诊断快照中。
- [x] 本工单不发 HTTP、不申请 breaker permission。

**Design:** [architecture §6.1](../../../tech/model-scheduling/architecture.md#61-两阶段候选规划)
