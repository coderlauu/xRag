# 07 — 问答 SSE emitter adapter

**Blocked by:** 06  
**Status:** complete（2026-08-12；目标测试与 `./mvnw -q -B verify` 通过）

实现问答事件映射、sequence、delta 合并、有界 mailbox、push executor 与唯一发送守卫。

**Acceptance:** `3C-SSE-01~08`；send 线程非 Reactor event loop；背压不丢 token 而结算失败。
