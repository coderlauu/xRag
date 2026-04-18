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
2. docs/status/v8-phase-3b.md
3. docs/exec-plans/active/2026-04-18-phase-3b-release-readiness.md
4. docs/handoff/v8.md

以后用中文回答。

当前版本是 v8 / Phase 3B。
当前节点是 testing-and-release-readiness。
Lane 0 / Lane A / Lane B / Lane C / Lane D 已完成，implementation-lanes 已完成并已转入 completed。

当前边界：
- 不要扩大 v7 / Phase 3A 或 v8 / Phase 3B scope。
- 不要修改 schema、shared-types、API path、DTO、OpenAPI、API client、状态枚举或 recovery 状态机；如果必须改，先走 contract-guard。
- 不要把自动 remediation / 自动 rerun / 自动 rollback 混进 Phase 3B。
- 继续复用 v7 diagnostic read models，不要重建诊断影子数据。

本地已完成验证：
- pnpm test:integration
- pnpm test:e2e
- pnpm e2e:smoke
- pnpm test:unit
- pnpm harness:check
- pnpm docs:check
- git diff --check

已知说明：
- pnpm validate 当前会在 pnpm contract:check 因未提交的 generated OpenAPI diff 按脚本预期停止；不要把它误判为 Lane D 回归。

下一步只做 release-readiness / closeout：
- 补齐并复核剩余验证记录
- 保持 docs/handoff/current.md、docs/status/v8-phase-3b.md、docs/handoff/v8.md、AGENTS.md 同步
- 如果进入提交前收口，准备 main commit 与 GitHub Actions evidence
```
