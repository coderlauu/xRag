# 03 — Provider seam 与可编程假实现

**What to build:** 在工单 01 的领域类型上定义四个按能力分开的 Provider SPI，并提供可编程、确定性、无网络的假实现。

**Blocked by:** 无（01 已于 2026-08-02 完成）

**Status:** done（2026-08-03）

## Slice

- 定义同步 Chat、流式 Chat、Embedding、Rerank 四个内部 Provider SPI；不创建万能请求/响应 DTO。
- Provider 协议 DTO 与供应商原始错误不得越过 adapter 包。
- `ProgrammableFakeProvider` 可脚本化返回成功、400/401/403/429/5xx、超时、断流、畸形数据与取消。
- 假实现记录调用参数、请求次数和取消/释放事实，支持可控时钟或事件源。
- 本工单不实现路由、预算、breaker、诊断 writer 或真实 HTTP adapter。

## Acceptance

- [x] 四个 SPI 的输入/输出只使用领域类型或能力专用类型。
- [x] 假实现不依赖 HTTP、数据库、Spring 事务或真实等待。
- [x] 同一脚本重复运行产生一致事件和计数。
- [x] secret、raw body 与供应商异常类型不能越过 seam。
- [x] `./mvnw -B verify` 通过（229 tests，0 failures，2 skipped）。

**Design:** [architecture §5.2](../../../tech/model-scheduling/architecture.md#52-provider-spi)
