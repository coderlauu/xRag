# 13 — 竞态、背压与压力测试

**Blocked by:** 12  
**Status:** complete（2026-08-12）

运行完成/停止/超时 1000 轮竞态，覆盖 mailbox 饱和、队首取消、executor 拒绝与关停。

**Acceptance:** `3C-SET-04` 及 QUE/SSE/LIFE 压力项；每轮资源计数回基线，失败可复现定位。
