# 04 — FIFO 队列与公平许可

**Blocked by:** 01  
**Status:** complete（2026-08-12；目标测试与 `./mvnw -q -B verify` 通过）

实现单实例 FIFO 队列、公平许可、绝对等待 deadline、取消/超时惰性移除和队列满拒绝。

**Acceptance:** `3C-QUE-01~08`；确定性时钟；零数据库轮询；压力后许可回基线。
