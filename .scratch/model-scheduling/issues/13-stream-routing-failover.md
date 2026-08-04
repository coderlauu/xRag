# 13 — 流式路由与故障转移

**What to build:** 把 RoutePlan、Deadline/错误动作和 breaker 接入单次订阅流状态机，实现提交前透明切换与提交后禁止混流。

**Blocked by:** 07, 08, 09, 12

**Status:** done

## Slice

- 每个候选订阅前申请 breaker 许可；非 HTTP 决策写 ordered route event。
- Provider protocol violation、首事件/首有效超时、网络/5xx/空流按唯一动作表在提交前恢复。
- `STREAM_STARTED` 后错误不 retry、不切换，保存准确 failure phase。
- 成功、失败与取消手工结算许可，完整 candidates/events/attempts/首包时刻异步诊断。
- 预提交全部候选耗尽时向订阅者返回约定异常，不发 `STREAM_FAILED(incomplete=true)`。

## Acceptance

- [x] STR-08、STR-14、STR-18、STR-19 与流式 breaker 集成自动化（half-open 许可占满按 breaker 决策换候选）。
- [x] STR-28、STR-31~STR-33 自动化：预提交耗尽零事件走 onError，探测期 reset 可切换，提交后协议失败不切换且正确结算。
- [x] STR-34 自动化：提交前合法 Provider error SSE event 映射 `SERVER_ERROR + UPSTREAM_STREAM`，计 breaker 并按预算切换。
- [x] DIA-06 自动化：提交后中断持久化为 `STREAM_FAILED`、`committed=true` 且保留唯一贡献 deployment（唯一带 `downstreamCommitMs` 的 attempt）。
- [x] 首选失败时下游看不到首选事件；详情按真实时间顺序显示失败、切换和成功。
- [x] 提交后不会出现第二个 deployment 的事件。
- [x] route event 与 failure phase 足以还原 permission、timeout 和 failover 决策。
- [x] `./mvnw -B verify` 通过（336 tests，0 failures，2 skipped）。

**Design:** [PRD FR-04](../../../docs/prd/2026-07-28-model-scheduling-prd.md#fr-04-流式调用)

## Notes

- 多候选切换没有塞进 `SingleCandidateStreamingChatClient`：单次 attempt 的机制（六阶段超时、
  探测上限、有效业务块判定、上游 complete 阶段区分、失败归一）提取为包内
  `CandidateStreamAttempt`，成帧与诊断留在调用方；`RoutingStreamingChatClient` 是新增的路由协调层。
- 流式不实现原地重试：`streamChat` 的 `maxSameDeploymentAttempts` 为 1（架构文档
  「首有效块超时不原地重试」），`AttemptBudget` 也会拒绝，故只实现 FAILOVER。
- 总超时放在每个 attempt 内部（取共享 `Deadline.remaining()`），不放最外层——放外层会让
  total-deadline 错误绕过 attempt 的 `onErrorResume`，导致 breaker permission 不结算。
- 已知缺口（不在本工单范围）：路由用 `RouteRequest.capability(STREAM_CHAT, ...)`，
  它的 `toolCallingRequired` 恒为 false，带 tools 的流式请求不会按 `TOOL_CALLING` 过滤候选。
