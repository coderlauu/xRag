# 03 — 不可信输入 codec 与 Prompt 预算

**Blocked by:** 01  
**Status:** complete（2026-08-12；目标测试与 `./mvnw -q -B verify` 通过）

实现两消息固定结构、`UntrustedPromptCodec`、完整条目淘汰、窗口预算与攻击样例。

**Acceptance:** `3C-PRM-01~07`；超预算零模型调用；四类输入不能逃逸数据结构。
