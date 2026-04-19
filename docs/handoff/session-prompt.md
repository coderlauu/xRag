# Session Resume Prompt

本文件是稳定路径。新会话里可以直接让模型先读取 `docs/handoff/session-prompt.md`，再按其中说明继续。

推荐首句：

```text
请先读取 docs/handoff/session-prompt.md，并按其中说明继续。以后用中文回答。
```

---

```text
你在 /Users/coderlauu/xRag。

先读：
1. docs/handoff/current.md
2. docs/handoff/v8.md
3. docs/status/v8-phase-3b.md

以后用中文回答。

当前状态：v8 / Phase 3B 已全部完成并归档。
- closeout commit: 0de02de
- GitHub Actions run: 24602471351 success
- 所有 milestones: completed
- exec plan 已移至 docs/exec-plans/completed/

当前无 active 版本，下一步是启动 v9。

启动 v9 时的约束：
- 必须按 new-version 流程创建 docs/handoff/v9.md 与 docs/status/v9-*.md
- 不得反向扩大 v8 / Phase 3B scope
- 任何 schema、shared-types、API path、DTO、OpenAPI、API client、状态枚举变更必须先走 contract-guard
- v8 recovery action contract 作为上游基线，v7 diagnostic read models 继续复用，不重建诊断影子数据
- v4 / Phase 2A 继续作为主数据模型与生产回滚基线

v8 验证记录（已完成）：
- pnpm test:integration: 23 tests passed
- pnpm test:e2e: 10 tests passed
- pnpm e2e:smoke: 10 tests passed
- pnpm test:unit: 41 tests passed
- pnpm harness:check: passed
- pnpm docs:check: passed
- git diff --check: passed
- GitHub Actions CI: 24602471351 success
```
