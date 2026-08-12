# 06 — 生成编排深模块

**Blocked by:** 03, 05  
**Status:** complete（2026-08-12；目标测试与 `./mvnw -q -B verify` 通过）

实现 `GenerationTasks.submit/stop`，只经 `RoutingStreamingChatClient`，编排 Prompt、队列、订阅和结算。

**Acceptance:** 假流式 adapter 跨外部 seam 验证正常/失败/停止；工具未结算零模型调用；内部 seam 不外泄。
