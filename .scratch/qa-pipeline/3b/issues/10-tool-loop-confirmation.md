# 10 — 工具调用循环与副作用确认 API

**What to build:** 原生 Function Calling 循环、副作用二次确认端点与工具执行。

**Blocked by:** 09
**Status:** complete

## Slice

- 一次 Chat 调用完成工具选择与参数抽取；工具清单为空时不发送 `tools` 字段。
- 有副作用工具进 `PENDING_CONFIRM`，**期间零外部调用**。
- `POST .../tool-calls/{id}/decision`，`CONFIRM`/`CANCEL` 同端点，`clientRequestId` 幂等。
- 失败重试产生新 `toolCallId`，有副作用者重新回到 `PENDING_CONFIRM`。
- 工具返回按纯文本存取；失败只暴露归一化文案。

## Acceptance

- [x] 3B-TOOL-09、11~18 自动化通过（10 属工单 02 的数据库约束，03/07 已在本组补覆盖）。
- [x] 可观测假工具断言 `PENDING_CONFIRM` 期间调用次数为 0。
- [x] 重复 `CONFIRM` 同 key 只执行一次（幂等重放返回同一结果，不是 409）；取消后确认 409。
- [x] 换一个 Repository 实例重读仍是 `PENDING_CONFIRM`——状态在库不在前端内存。
- [x] 超时归一化为闭集码，`result_text` 为 null，不透传异常。

**Evidence:** `ToolCallServiceIntegrationTests`（12 项）。

**V9 漏了幂等键，用 V10 向前修复**：API §10 冻结了"同 clientRequestId 重复 CONFIRM 只执行一次"，
但表里没有承载它的列。只靠状态 CAS 不够——CAS 能保证只执行一次，但第二次请求会拿到 409，
而契约说的是拿到同一个结果。不改写 V9：它已经应用到本地开发库与测试 schema（数据模型 §17）。

**数据库约束纠正了我的一处实现**：原本想给 `UNAVAILABLE` / `ABORTED` 也存 failure_code，
被 `ck_qa_tool_failure` 挡下。约束是对的——那两个状态本身就是原因，`failure_code` 只属于
"执行了但失败了"。改的是代码不是迁移。

**传输方式仍是 Step C 的缺口**：本工单定义了 `ToolExecutor` SPI（不抛异常、自己守 timeout），
但没有任何生产实现。白名单里有工具却没有对应 executor 时**启动失败**——放行只会让问题推迟到
用户点了"确认执行"之后，那时界面上和真正的工具故障看起来一模一样。
**Step D 演示"至少一个受控工具调用"之前，需要先定这个传输契约（MCP over HTTP 还是别的）。**
