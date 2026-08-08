# 12 — 前端导航、API types 与共享状态基础

**What to build:** 为已确认两页建立正式路由、导航、强类型 API client、ProblemDetail 映射和组件测试基础；不实现页面业务或复制原型。

**Blocked by:** —
**Status:** complete

## Slice

- 增加 `/chat`、`/intent-tree` 路由和顶级导航；3A 不改根路径默认入口。
- 定义 conversation/turn/inspector/tree/revision DTO 与 error code 映射。
- 建立 cursor、loading/error/empty、toast/modal、viewport 测试 harness 的最小复用。
- 页面先用明确“尚未加载”骨架，不内嵌假业务数据。

## Acceptance

- [x] 前端 typecheck/build/lint/test 通过，现有知识库/诊断路由无回归。
- [x] 未知 API code 有安全通用文案，已知冲突保留结构化扩展。
- [x] 根路径在 3A 仍保持知识库，符合 Step B P-07。
- [x] 本工单不复制 `prototype/qa-pipeline/**` JS/CSS 或实现画布/聊天状态。

**Evidence:** `qa-foundation.test.tsx`、`api/qa.test.ts`、`EmptyState.test.tsx`；
`2026-08-07 pnpm test --run && pnpm build && pnpm lint` 为 19 tests / 4 files 全通过。

**Design:** [conversation ui](../../../../tech/qa-pipeline/conversation/ui-spec.md)、[intent ui](../../../../tech/qa-pipeline/intent-tree/ui-spec.md)
