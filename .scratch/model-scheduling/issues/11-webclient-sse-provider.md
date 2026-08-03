# 11 — WebClient 与 SSE Provider 适配

**What to build:** 在保持 MVC/Tomcat 服务端的前提下，引入 WebClient/Reactor Netty，打通一个 deployment 的 SSE 语法解析和 Provider 事件归一化。

**Blocked by:** 无（03 已于 2026-08-03 完成）

**Status:** done（2026-08-03）

## Slice

- 增加 reactive client 依赖，并断言应用仍为 Servlet web application。
- 使用 `ServerSentEvent<String>` 解析 SSE，不手写 TCP/readLine parser。
- OpenAI-compatible adapter 归一文本、工具、拒答、usage、done/error。
- 单事件 64KiB 上限；未知合法事件可诊断跳过；raw body 不越过 seam。
- 用本地 Reactor Netty HTTP server 构造 TCP 分片、多行 data 与断流。

## Acceptance

- [x] STR-01~STR-09 自动化。
- [x] JSON 跨 TCP chunk、多事件同 chunk、多 data 行均正确。
- [x] 畸形 Provider 数据映射为 Provider protocol violation，可由上层动作表判定为提交前可切换。
- [x] 未订阅底层 Provider publisher 不发 HTTP。
- [x] 本工单不定义公共流是否可重复订阅，也不实现 route failover。

实现说明：Spring 6.2 的 SSE reader 原生识别 LF/CRLF，但不识别独立 CR；adapter 在 `DataBuffer` 进入 `ServerSentEvent<String>` reader 前仅归一行尾，事件边界、`data:` 合并、注释和解码仍由 Spring 负责，未引入 TCP/readLine parser。`./mvnw -B verify` 通过（294 tests，0 failures，2 skipped）。

**Design:** [ADR 0004](../../../docs/adr/0004-reactive-streaming-runtime.md)
