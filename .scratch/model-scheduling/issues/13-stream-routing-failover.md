# 13 — 流式路由与故障转移

**What to build:** 把 RoutePlan、Deadline/错误动作和 breaker 接入单次订阅流状态机，实现提交前透明切换与提交后禁止混流。

**Blocked by:** 07, 08, 09, 12

**Status:** ready

## Slice

- 每个候选订阅前申请 breaker 许可；非 HTTP 决策写 ordered route event。
- Provider protocol violation、首事件/首有效超时、网络/5xx/空流按唯一动作表在提交前恢复。
- `STREAM_STARTED` 后错误不 retry、不切换，保存准确 failure phase。
- 成功、失败与取消手工结算许可，完整 candidates/events/attempts/首包时刻异步诊断。
- 预提交全部候选耗尽时向订阅者返回约定异常，不发 `STREAM_FAILED(incomplete=true)`。

## Acceptance

- [ ] STR-08、STR-14、STR-18、STR-19 与流式 breaker 集成自动化。
- [ ] STR-28、STR-31~STR-33 自动化：预提交耗尽走 onError，探测期 reset 可切换，提交后协议失败不切换且正确结算。
- [ ] STR-34 自动化：提交前合法 Provider error SSE event 映射 `SERVER_ERROR + UPSTREAM_STREAM`，计 breaker 并按预算切换。
- [ ] DIA-06 自动化：提交后中断持久化为 `STREAM_FAILED`、`committed=true` 且保留唯一贡献 deployment。
- [ ] 首选失败时下游看不到首选事件；详情按真实时间顺序显示失败、切换和成功。
- [ ] 提交后不会出现第二个 deployment 的事件。
- [ ] route event 与 failure phase 足以还原 permission、timeout 和 failover 决策。
- [ ] `./mvnw -B verify` 通过。

**Design:** [PRD FR-04](../../../docs/prd/2026-07-28-model-scheduling-prd.md#fr-04-流式调用)
