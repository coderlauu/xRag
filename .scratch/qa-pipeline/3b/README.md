# Phase 3B 原子工单：检索 + 工具

本目录把 [3B 分角色任务书](../../../docs/exec-plans/active/2026-08-08-qa-pipeline-3b-implementation-lanes.md)
转成 16 张可独立验收的工单。Step C 已于 **2026-08-08 获确认**；第一前沿 **01、02** 已解锁，其余按依赖图逐批解锁。

## 状态

| # | 工单 | Blocked by | 状态 |
|---:|---|---|---|
| 01 | [3B 配置对象与启动校验](issues/01-retrieval-config.md) | — | complete |
| 02 | [V9 迁移与 3B DDL 约束](issues/02-v9-schema.md) | — | complete |
| 03 | [中文切词器与关键词索引写入点](issues/03-keyword-indexing.md) | 02 | complete |
| 04 | [关键词存量回填批处理](issues/04-keyword-backfill.md) | 03 | complete |
| 05 | [`ChunkSearchPort` 与三个通道](issues/05-search-channels.md) | 01 | complete |
| 06 | [RRF 融合、去重与两段截断](issues/06-fusion-dedup.md) | 05 | complete |
| 07 | [精排调用与三态传导](issues/07-rerank-tri-state.md) | 06 | complete |
| 08 | [检索编排、部分失败与重试](issues/08-retrieval-orchestration.md) | 05, 06, 07 | blocked |
| 09 | [工具白名单、Schema 复核与状态机](issues/09-tool-registry-validation.md) | 02 | complete |
| 10 | [工具调用循环与副作用确认 API](issues/10-tool-loop-confirmation.md) | 09 | complete |
| 11 | [3B API 契约与隐私边界](issues/11-3b-api-contract.md) | 08, 10 | blocked |
| 12 | [前端来源区与降级表达](issues/12-frontend-sources.md) | 11 | blocked |
| 13 | [前端工具区与确认卡](issues/13-frontend-tools.md) | 10, 12 | blocked |
| 14 | [隐私审计、故障注入与 3B E2E](issues/14-privacy-e2e.md) | 11, 13 | blocked |
| 15 | [矩阵执行与浏览器验收](issues/15-matrix-validation.md) | 01~14 | blocked |
| 16 | [CI、部署配置与 3B 门禁](issues/16-ci-deploy.md) | 01~15 | blocked |

## 依赖图

```text
01 ──┬─> 05 ──> 06 ──> 07 ──> 08 ──> 11 ──> 12 ─┐
     │                          ↑                ├─> 14 ──> 15 ──> 16
02 ──┼─> 03 ──> 04             09 ──> 10 ──> 13 ─┘
     └─> 09
```

01~07、09、10 已完成（2026-08-08~09），解锁 **08**（检索编排）。
11 需要 08；12/13 需要 11。

## 推进规则

- 一张工单只提交一个可验证变化；发现隐藏依赖先改本图，不把下游契约塞进当前工单。
- Acceptance 里的测试 ID 来自[测试矩阵 §18~§25](../../../tech/qa-pipeline/test-matrix.md)，
  自动化未落地不得关闭。
- 后端不绕过 `com.app.ai`；问答模块不拼 chunk 表 SQL；模型与工具调用都不在数据库事务内。
- 前端不复制原型代码；布局已是 C，本段不再改布局。
- 不使用 `git add -A` / `git commit -a`；共享工作区有并行任务时只暂存明确文件。
- **`3B-RANK-08`（真实 Rerank）依赖 Phase 2 工单 23**，未落地时写 blocked，不伪造 passed。
  理由与影响见[任务书 §7](../../../docs/exec-plans/active/2026-08-08-qa-pipeline-3b-implementation-lanes.md)。
