# 06 — 单候选同步 Chat tracer

**What to build:** 用一个显式候选打通同步 `ChatClient`，并实现最小 OpenAI-compatible `RestClient` adapter：能力命令 → 一个 HTTP attempt → 结果或统一异常 → 完成态诊断。

**Blocked by:** 无（03、04、08 已于 2026-08-03 完成）

**Status:** done（2026-08-03）

## Slice

- 按 API 契约定义封闭 `ChatMessage`、工具传输 DTO、`ChatFinishReason`、`TokenUsage`、`ChatCommand/ChatResult/ExecutionInfo` 与携带执行事实的 `ModelCallException`。
- 第一版只执行一个调用方已指定候选，不提前实现规划、重试、breaker 或故障转移。
- 用 Apache HttpClient 5 的 per-request 配置把 `Deadline` 计算出的剩余阶段上限绑定到底层 `RestClient` 请求；取消回调必须 abort 当前请求并在终态注销。
- OpenAI-compatible adapter 与可编程假 Provider 都返回实际 deployment、finish reason、usage 和 request ID 脱敏值。
- 成功与失败都生成 candidate、attempt、failure phase 和 terminal route event。
- 不新增面向最终用户的 Chat HTTP endpoint，也不实现 diagnostics calls API。

## Acceptance

- [x] SYN-01、SYN-29、SYN-30 与对应成功/失败诊断自动化；锁定公开消息形状、finish reason 归一和 usage 缺失语义。
- [x] SYN-21~SYN-25、SYN-27 自动化，逐项验证剩余 deadline 下传、主动 abort、终态竞态、body timeout phase、预取消 registration，以及持续滴入数据也不能绕过绝对总 deadline。
- [x] 成功结果和失败异常都能关联 logical call；异常只暴露 `ExecutionInfo/TerminalCause/retryableByCaller` 及稳定消息。
- [x] Provider DTO 与原始异常不越过 provider 包。
- [x] 只有 adapter 真正开始 outbound dispatch，或假 Provider 显式模拟该时刻后，才创建 attempt。
- [x] 剩余 deadline 能缩短底层 request timeout；取消能终止在途 stub 请求，且清理后注销回调。
- [x] `./mvnw -B verify` 通过（243 tests，0 failures，2 skipped）。

**Design:** [API §1.3](../../../tech/model-scheduling/api.md#13-chat)
