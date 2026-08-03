# ADR 0004：流式 Provider 客户端采用 WebClient 与 Reactor

- 状态：已接受（2026-08-02）
- 日期：2026-07-28
- 相关：[Phase 2 PRD](../prd/2026-07-28-model-scheduling-prd.md)、[技术方案](../../tech/model-scheduling/architecture.md)

## 背景

xrag 当前是 Spring MVC/Tomcat 应用，有限响应使用同步 `RestClient`。流式 Chat 还要保证 SSE 事件解析、首个有效业务块探测、有界缓冲、下游取消、背压和一次性资源清理。阻塞读取加专用线程池可以产出字节流，但取消在途 HTTP、限制慢下游缓冲和处理提交前/后的不同故障会形成一套自研异步运行时。

## 决策

有限响应继续使用 `RestClient`；流式 Provider 客户端引入 `spring-boot-starter-webflux`，用 `WebClient`、Reactor Netty 和 `Flux<ChatStreamEvent>` 实现。应用服务器仍是 Spring MVC/Tomcat，不迁移为 WebFlux Server。

调度器在首次订阅时申请熔断许可，在首个有效业务块已缓冲后发出 `STREAM_STARTED` 并形成下游提交点。提交前可以取消当前上游并切换候选；若全部候选在提交前耗尽，Publisher 不发送业务事件而以 `ModelCallException` 结束。提交后中断只能发 `STREAM_FAILED`。订阅取消必须传播到当前 WebClient 请求，所有终态汇合到同一清理路径。

## 后果

- 业务流式端口显式绑定 Reactor 的取消与背压语义；若未来更换响应式库，需要适配调用方。
- 每次调用 `stream(...)` 返回单订阅 Publisher；第二次订阅在发起 HTTP 前失败。调用方若确实要重新生成，必须重新调用端口，不能用外层 `retry` 悄悄绕过调度预算并产生重复计费。
- 后端同时包含 Servlet 与 WebClient 的 reactive client 依赖，但不出现两套服务端路由模型。
- 同步代码不为统一技术栈而被无收益地 reactive 化。
- SSE 解析优先复用 Spring 的事件解码器，不维护按行读取的自研解析器。
