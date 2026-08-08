# 07 — 查询归一化、改写与 JSON 容错

**What to build:** 实现确定性最小归一化、一次 BALANCED 改写调用和封闭解析器；失败回单问题，4+ 子问题显式拒绝。

**Blocked by:** 01
**Status:** complete

## Slice

- NFKC、空白处理与空消息拒绝。
- 渲染摘要/近期历史/当前问题，调用 ChatClient，关联 logicalCallId。
- code fence、object/字段、空项、重复和顺序校验。
- 1~3 正常，4+ 返回 typed `TooManyTopics`，不 `limit(3)`。

## Acceptance

- [x] 3A-RW-01~12 自动化通过。
- [x] 解析异常不抛出供应商/JSON 细节，fallback 使用 normalized question。
- [x] 模型原始 JSON/reason 不进入结果类型、数据库、API 或日志。
- [x] 本工单不实现意图、检索或最终回答。

**Evidence:** `QueryRewriteServiceTests` 覆盖 NFKC、code fence、字段/数组容错、保序去重、4+ 主题、
模型失败、Prompt 边界、BALANCED 与 logicalCallId；`2026-08-07 ./mvnw -q verify` 通过。

**Design:** [architecture §7](../../../../tech/qa-pipeline/architecture.md)
