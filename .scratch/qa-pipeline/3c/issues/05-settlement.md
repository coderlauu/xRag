# 05 — 单一结算器与持久化

**Blocked by:** 02, 04  
**Status:** complete（2026-08-12；目标测试与 `./mvnw -q -B verify` 通过）

实现 `TaskSettlement.trySettle` CAS、task/turn/message 同事务、部分回答与 saved=false 语义。

**Acceptance:** `3C-DB-04~07`、`3C-HIST-01`、`3C-SET-01~03`；每任务最多一次副作用。
