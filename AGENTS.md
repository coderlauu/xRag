# AGENTS.md

本文件是仓库入口，不是百科全书。

## 1. 先看哪里

1. [当前 Handoff](/Users/coderlauu/xRag/docs/handoff/current.md)
2. [Harness Engineering Playbook](/Users/coderlauu/xRag/docs/process/2026-03-31-harness-engineering-playbook.md)
3. [Architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md)
4. [Data Model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md)
5. [API Design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)

## 2. 当前项目状态

- 当前仓库还处于 `原型 + 技术方案` 阶段
- 正式工程尚未 scaffold
- `prototype/` 是产品验证资产，不是正式实现

## 3. Repo 里的事实来源

- `docs/handoff/current.md`
  - 当前版本边界、技术结论、默认实施顺序
- `tech/`
  - 正式工程实现的架构、数据模型、API 事实来源
- `docs/process/2026-03-31-harness-engineering-playbook.md`
  - agent 和工程师共用的流程、标准、DoD
- `docs/exec-plans/`
  - 复杂任务执行计划
- `docs/generated/`
  - 自动生成资产，如 OpenAPI、ERD、schema snapshot

## 4. 工作规则

1. 开工前先读 `current.md`
2. 大于半天的任务先写 `docs/exec-plans/active/*.md`
3. 行为变更必须同时更新对应文档
4. API 变更必须更新 OpenAPI 和前端 SDK
5. 任务完成前至少做相关层级验证
6. 不要把产品约束只留在聊天记录里

## 5. 未来正式工程建议结构

```text
apps/web
apps/api
apps/worker
packages/api-client
packages/shared-types
packages/ui
```

## 6. 验证约定

正式工程 scaffold 后，常用命令应统一收敛到：

- `pnpm validate`
- `pnpm test:unit`
- `pnpm test:integration`
- `pnpm test:e2e`
- `pnpm e2e:smoke`

当前仓库还没有这些命令，新增工程时应一并补齐。
