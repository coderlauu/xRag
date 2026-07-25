# 08 — 上传并发限流

**What to build:** 同时上传的请求数超过上限时，多出来的请求快速收到 429 而不是一起把磁盘和 IO 压满。

这张工单的全部技术含量在**拦截位置**：必须在 `Filter` 里，不能在 `HandlerInterceptor` 里。原因是 multipart 请求体由 `DispatcherServlet` 解析，时机在 `Filter` 之后、`Interceptor` 之前——放在 `Interceptor` 里限流时文件已经完整读进来了，保护磁盘和 IO 的目的已经落空。

**Blocked by:** 07（文件上传）

**Status:** ready-for-agent

- [ ] 实现 `Filter`，用 `java.util.concurrent.Semaphore`（**不用 Redisson**——单实例下本地信号量能力等价且零依赖，理由见 [ADR 0002](../../../docs/adr/0002-knowledge-base-async-and-concurrency.md)）
- [ ] `tryAcquire` 带超时（默认 5s），拿不到许可返回 `429 UPLOAD_BUSY`，不无限期排队
- [ ] 许可在 `finally` 中释放，任何异常路径都不泄漏
- [ ] Filter 只作用于上传接口路径，不影响其他接口
- [ ] `app.upload.max-concurrent` 可配置，默认 3
- [ ] 手工验证：把 `max-concurrent` 设为 1，同时发起 3 个上传，确认 2 个返回 429
- [ ] 手工验证：被 429 拒绝的请求**没有产生临时文件**（这是拦截位置正确性的直接证据——如果产生了临时文件，说明请求体已被解析，拦截位置错了）
- [ ] 前端把 429 展示为"上传繁忙，请稍后重试"而不是通用失败
- [ ] `./mvnw -q -B verify` 通过
