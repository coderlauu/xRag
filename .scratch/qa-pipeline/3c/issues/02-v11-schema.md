# 02 — V11 任务与消息迁移

**Blocked by:** —  
**Status:** complete（2026-08-12；目标测试与 `./mvnw -q -B verify` 通过）

新增 `qa_generation_task`，扩展 turn/message/stage 闭集和约束，不改写旧 migration。

**Acceptance:** `3C-DB-01~03`；V10→V11 与空库迁移都通过；非法组合被数据库拒绝。
