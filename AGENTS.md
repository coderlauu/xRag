# AGENTS.md

本文件是仓库入口，不是百科全书。

## 1. 先看哪里

1. [当前 Handoff](/Users/coderlauu/xRag/docs/handoff/current.md)
2. [当前版本状态](/Users/coderlauu/xRag/docs/status/v1-phase-1a.md)
3. [Harness Engineering Playbook](/Users/coderlauu/xRag/docs/process/2026-03-31-harness-engineering-playbook.md)
4. [Architecture](/Users/coderlauu/xRag/tech/architecture/2026-03-31-phase-1a-architecture.md)
5. [Data Model](/Users/coderlauu/xRag/tech/data-model/2026-03-31-phase-1a-data-model.md)
6. [API Design](/Users/coderlauu/xRag/tech/api/2026-03-31-phase-1a-api.md)

## 2. 当前项目状态

- 当前仓库已经完成正式工程 scaffold
- `v1 / Phase 1A` 仍在开发中，当前状态以 `docs/status/` 为准
- `prototype/` 是产品验证资产，不是正式实现

## 3. Repo 里的事实来源

- `docs/handoff/current.md`
  - 当前版本边界、技术结论、默认实施顺序
- `docs/status/`
  - 当前版本进度、阻塞项、最近验证、resume 入口
- `tech/`
  - 正式工程实现的架构、数据模型、API 事实来源
- `docs/process/2026-03-31-harness-engineering-playbook.md`
  - agent 和工程师共用的流程、标准、DoD
- `docs/exec-plans/`
  - 复杂任务执行计划
- `docs/generated/`
  - 自动生成资产，如 OpenAPI、ERD、schema snapshot

## 3.1 项目级 Skill

- 项目内 skill 路径：
  - [xrag-iteration-governor](/Users/coderlauu/xRag/.codex/skills/xrag-iteration-governor/SKILL.md)
- 用途：
  - 新需求分流
  - 判断是否需要新版本 handoff、status 或 exec plan
  - 脚手架生成版本文档
  - 收口版本归档流程
- 如果当前 Codex 运行环境不自动发现项目内 skill，可执行：
  - `/Users/coderlauu/xRag/.codex/skills/xrag-iteration-governor/scripts/install_to_codex_home.sh`

## 4. 工作规则

1. 开工前先读 `current.md`
2. 大于半天的任务先写 `docs/exec-plans/active/*.md`
3. 当前版本主链路变化时，同步更新 `docs/status/*.md`
4. 行为变更必须同时更新对应文档
5. API 变更必须更新 OpenAPI 和前端 SDK
6. 任务完成前至少做相关层级验证
7. 不要把产品约束只留在聊天记录里

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
