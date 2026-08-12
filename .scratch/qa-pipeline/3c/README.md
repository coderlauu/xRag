# Phase 3C 原子工单：生成 + 流式 + 任务管控

Step C 已于 **2026-08-12 获确认**，共 16 张工单；**01~16 complete**。

| # | 工单 | Blocked by | 状态 |
|---:|---|---|---|
| 01 | [配置与领域闭集](issues/01-config-domain.md) | — | complete |
| 02 | [V11 任务与消息迁移](issues/02-v11-schema.md) | — | complete |
| 03 | [Prompt codec 与预算](issues/03-prompt-safety.md) | 01 | complete |
| 04 | [FIFO 队列与公平许可](issues/04-fair-queue.md) | 01 | complete |
| 05 | [单一结算器与持久化](issues/05-settlement.md) | 02, 04 | complete |
| 06 | [生成编排深模块](issues/06-generation-module.md) | 03, 05 | complete |
| 07 | [SSE emitter adapter](issues/07-sse-adapter.md) | 06 | complete |
| 08 | [流式提交与停止 API](issues/08-stream-stop-api.md) | 07 | complete |
| 09 | [断连、恢复与生命周期](issues/09-recovery-lifecycle.md) | 08 | complete |
| 10 | [前端流式状态与停止](issues/10-frontend-stream.md) | 08 | complete |
| 11 | [前端重连、未保存与无障碍](issues/11-frontend-recovery.md) | 09, 10 | complete |
| 12 | [Prompt/协议隐私审计](issues/12-privacy.md) | 09, 11 | complete |
| 13 | [竞态、背压与压力测试](issues/13-race-pressure.md) | 12 | complete |
| 14 | [3C E2E 与冻结契约回归](issues/14-e2e-regression.md) | 13 | complete |
| 15 | [矩阵回写与浏览器验收](issues/15-matrix-browser.md) | 14 | complete |
| 16 | [CI、部署与 Phase 3 门禁](issues/16-ci-deploy.md) | 15 | complete |

推进规则：一张工单一个可验证变化；真实接线必须由 08 覆盖；测试只跨深模块外部 seam；未有证据不得
把矩阵状态标 passed；前端不复制原型；不绕过 `com.app.ai`；不提前实现 Phase 4。
