# Phase 3A 原子工单：记忆 + 改写 + 意图

本目录把 [3A 分角色任务书](../../../docs/exec-plans/active/2026-08-07-qa-pipeline-3a-implementation-lanes.md)
转成 17 张可独立验收的工单。Phase 3A Step C 已于 2026-08-07 获确认；01~15 已完成，当前第十前沿
推进 16（含需项目所有者确认的浏览器验收闸门）。

## 状态

| # | 工单 | Blocked by | 状态 |
|---:|---|---|---|
| 01 | [领域词汇、配置与 Prompt catalog](issues/01-domain-config-prompts.md) | — | complete |
| 02 | [问答业务 DDL 与迁移约束](issues/02-qa-schema.md) | — | complete |
| 03 | [对话/消息/本轮 Repository 与幂等](issues/03-conversation-repositories.md) | 02 | complete |
| 04 | [意图整树 revision Repository 与领域约束](issues/04-intent-tree-revisions.md) | 02 | complete |
| 05 | [对话生命周期 API 与中断恢复](issues/05-conversation-api-recovery.md) | 03 | complete |
| 06 | [历史快照、摘要与水位线](issues/06-memory-summary.md) | 01, 03 | complete |
| 07 | [查询归一化、改写与 JSON 容错](issues/07-query-rewrite.md) | 01 | complete |
| 08 | [有界并行意图评分与全局封顶](issues/08-intent-scoring.md) | 01, 04 | complete |
| 09 | [歧义引导、选择闭环与动作映射](issues/09-ambiguity-actions.md) | 08 | complete |
| 10 | [3A 理解编排与稳定终态](issues/10-understanding-orchestration.md) | 03, 05, 06, 07, 08, 09 | complete |
| 11 | [意图树管理 API 与影响预览](issues/11-intent-tree-api.md) | 04 | complete |
| 12 | [前端导航、API types 与共享状态基础](issues/12-frontend-foundation.md) | — | complete |
| 13 | [问答 A — Conversation + Inspector](issues/13-conversation-frontend.md) | 05, 10, 12 | complete |
| 14 | [意图树 C — Route Canvas](issues/14-intent-canvas-frontend.md) | 11, 12 | complete |
| 15 | [隐私审计、故障注入与跨 revision E2E](issues/15-privacy-failure-e2e.md) | 10, 11, 13, 14 | complete |
| 16 | [矩阵执行与双界面浏览器验收](issues/16-matrix-browser-validation.md) | 01~15 | ready |
| 17 | [CI、部署配置与 3A 门禁](issues/17-ci-deploy-gates.md) | 01~16 | blocked |

## 推进规则

- Step C 已获确认；01/02/12 可实现，完成后再按依赖图逐批解锁。
- 一张工单只提交一个可验证变化；发现隐藏依赖先改本图，不把下游契约塞进当前工单。
- Acceptance 中的测试 ID 来自 [3A 测试矩阵](../../../tech/qa-pipeline/test-matrix.md)，自动化未落地不得关闭。
- 前端不能复制原型代码，后端不能绕过 `com.app.ai`，任何模型调用都不在数据库事务内。
- 不使用 `git add -A` / `git commit -a`；共享工作区有并行任务时只暂存明确文件。
- 工单 16 才回写手工矩阵结果；工单 17 不把真实收费 Provider 设为 CI 条件。
