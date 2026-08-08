# 10 — 工具调用循环与副作用确认 API

**What to build:** 原生 Function Calling 循环、副作用二次确认端点与工具执行。

**Blocked by:** 09
**Status:** blocked

## Slice

- 一次 Chat 调用完成工具选择与参数抽取；工具清单为空时不发送 `tools` 字段。
- 有副作用工具进 `PENDING_CONFIRM`，**期间零外部调用**。
- `POST .../tool-calls/{id}/decision`，`CONFIRM`/`CANCEL` 同端点，`clientRequestId` 幂等。
- 失败重试产生新 `toolCallId`，有副作用者重新回到 `PENDING_CONFIRM`。
- 工具返回按纯文本存取；失败只暴露归一化文案。

## Acceptance

- [ ] 3B-TOOL-09~18 自动化通过。
- [ ] 用可观测假工具断言 `PENDING_CONFIRM` 期间调用次数为 0。
- [ ] 重复 `CONFIRM` 同 key 只执行一次；取消后确认返回 409。
- [ ] 刷新后 `PENDING_CONFIRM` 仍可裁决（状态在库不在前端）。
- [ ] 超时归一化为 `TOOL_EXECUTION_TIMEOUT`，不透传异常。
