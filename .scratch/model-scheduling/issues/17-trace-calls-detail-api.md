# 17 — Trace calls/detail API

**What to build:** 实现最近调用分页、筛选和详情 API，直接返回持久化的候选、route event、attempt、failure phase 与能力专用执行事实。

**Blocked by:** 04, 10, 13, 14, 15, 16

**Status:** blocked

## Slice

- 实现 calls 默认查询、分页、时间窗口和稳定排序。
- 支持 capability、多个 outcome、tier、provider、request ID 和 `failoverOccurred` 精确筛选。
- detail 按真实顺序返回 candidate → route event/attempt 时间线，不从 attempt 间隙推测 breaker 或预算事件。
- Stream 返回首事件/首有效/提交/终止阶段；Embedding 返回由成功 attempts 派生的 ordered contribution view 和 mixed/count 摘要；Rerank 返回 Noop 事实。
- 不修改 React 页面，不加入正文、raw error、完整 Provider request ID 或 secret。

## Acceptance

- [ ] API-02~API-11 自动化；API-09~API-11 分别锁定 failover、multi-outcome 与 attempt-based Provider 筛选。
- [ ] DIA-21 自动化：跨同步、流式和能力结果校验阶段抽样，call/attempt 与详情 API 返回架构映射表冻结的 error/phase。
- [ ] permission reject、预算拒绝、timeout phase、提交前切换和 mixed Embedding 均能从 API 精确还原。
- [ ] 不存在/已清理 call 返回稳定 404。
- [ ] 列表与详情中的实际执行事实一致。
- [ ] `./mvnw -B verify` 通过。

**Design:** [API §6~8](../../../tech/model-scheduling/api.md#6-最近调用)
