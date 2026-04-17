# AGENTS.md

本文件是仓库入口，不是百科全书。

## 1. 先看哪里

1. [当前 Handoff](/Users/coderlauu/xRag/docs/handoff/current.md)
2. [当前版本状态](/Users/coderlauu/xRag/docs/status/v6-phase-2c.md)
3. [Phase 2C Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-contract-freeze.md)
4. [当前 Testing And Release Readiness Exec Plan](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-17-phase-2c-testing-and-release-readiness.md)
5. [Phase 2C Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-architecture.md)
6. [Phase 2C Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-16-phase-2c-data-model.md)
7. [Phase 2C API Design](/Users/coderlauu/xRag/tech/api/2026-04-16-phase-2c-api.md)
8. [Phase 2C Contract Freeze Prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-16-phase-2c-contract-freeze-prerequisites.md)
9. [Phase 2C PRD](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-prd.md)
10. [Phase 2C Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-backlog.md)
11. [v6 Interaction Delta](/Users/coderlauu/xRag/design/spec/2026-04-16-v6-interaction-delta.md)
12. [Phase 2C P0 Technical Tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-16-phase-2c-p0-technical-tradeoffs.md)
13. [Phase 2B Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-13-phase-2b-contract-freeze.md)
14. [Phase 2A Contract Freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-contract-freeze.md)
15. [Phase 2A Runtime Contracts](/Users/coderlauu/xRag/tech/architecture/2026-04-08-phase-2a-runtime-contracts.md)
16. [Phase 2A Architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-07-phase-2a-architecture.md)
17. [Phase 2A Data Model](/Users/coderlauu/xRag/tech/data-model/2026-04-07-phase-2a-data-model.md)
18. [Phase 2A API Design](/Users/coderlauu/xRag/tech/api/2026-04-07-phase-2a-api.md)
19. [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)
20. [Harness Engineering Playbook](/Users/coderlauu/xRag/docs/process/2026-03-31-harness-engineering-playbook.md)

## 2. 当前项目状态

- 当前仓库已经完成正式工程 scaffold
- `v1 / Phase 1A`、`v2 / Phase 1B`、`v3 / Phase 1C`、`v4 / Phase 2A` 与 `v5 / Phase 2B` 已完成
- 当前活跃版本为 `v6 / Phase 2C`
- `v4 / Phase 2A` 已归档，并作为稳定工程与生产基线保留
- `v5 / Phase 2B` 已归档，GitHub Actions run `24514690725` 仍是当前已确认的 `v6 / Phase 2C` main 绿态基线
- `v6 / Phase 2C` 已完成 [Phase 2C Implementation Freeze](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-16-phase-2c-implementation-freeze.md) 与 [Phase 2C Implementation Lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-16-phase-2c-implementation-lanes.md)，当前进入 `testing-and-release-readiness`
- `prototype/` 是产品验证资产，不是正式实现

## 3. Repo 里的事实来源

- `docs/handoff/current.md`
  - 当前版本边界、技术结论、默认实施顺序
- `docs/status/`
  - 当前版本进度、阻塞项、最近验证、resume 入口
- `docs/prd/`
  - 当前版本产品目标、范围、验收、backlog 和非目标
- `docs/decisions/`
  - 跨文档 tradeoff、范围决策与实现前 gate 的事实来源
- `tech/`
  - 正式工程实现的架构、数据模型、API 与 runtime contract 事实来源；当前最新完整工程基线仍为 `v4 / Phase 2A`
- `docs/process/2026-03-31-harness-engineering-playbook.md`
  - agent 和工程师共用的流程、标准、DoD
- `docs/exec-plans/`
  - 复杂任务执行计划
- `docs/generated/`
  - 自动生成资产，如 OpenAPI、ERD、schema snapshot

## 3.1 项目级 Skill

- 项目内 skill 路径：
  - [xrag-iteration-governor](/Users/coderlauu/xRag/.codex/skills/xrag-iteration-governor/SKILL.md)
  - [implementation-lane](/Users/coderlauu/xRag/.codex/skills/implementation-lane/SKILL.md)
  - [contract-guard](/Users/coderlauu/xRag/.codex/skills/contract-guard/SKILL.md)
  - [answer-quality-gate](/Users/coderlauu/xRag/.codex/skills/answer-quality-gate/SKILL.md)
  - [technical-writer](/Users/coderlauu/xRag/.codex/skills/technical-writer/SKILL.md)
  - [doc-coauthoring](/Users/coderlauu/xRag/.codex/skills/doc-coauthoring/SKILL.md)
  - [requesting-code-review](/Users/coderlauu/xRag/.codex/skills/requesting-code-review/SKILL.md)
  - [systematic-debugging](/Users/coderlauu/xRag/.codex/skills/systematic-debugging/SKILL.md)
  - [webapp-testing](/Users/coderlauu/xRag/.codex/skills/webapp-testing/SKILL.md)
  - [frontend-design](/Users/coderlauu/xRag/.codex/skills/frontend-design/SKILL.md)
  - [vercel-react-best-practices](/Users/coderlauu/xRag/.codex/skills/vercel-react-best-practices/SKILL.md)
  - [web-design-guidelines](/Users/coderlauu/xRag/.codex/skills/web-design-guidelines/SKILL.md)
- 用途：
  - 新需求分流
  - 判断是否需要新版本 handoff、status 或 exec plan
  - 脚手架生成版本文档
  - 收口版本归档流程
  - 按冻结边界推进实现 lane
  - 保护 API、schema、shared-types 与状态机 contract
  - 保护 citation、scope、retrieval 与 answer 质量
  - 技术文档、设计文档与实现文档协作
  - 复杂 bug 排查、代码审查、Web 测试与前端实现
- 建议使用边界：
  - `xrag-iteration-governor`
    - 版本分流、exec plan、status、handoff、归档
  - `implementation-lane`
    - 在现有 PRD、handoff、status、exec plan 与 `tech/*` 边界内推进实现
  - `contract-guard`
    - API、schema、shared-types、状态枚举、状态机、OpenAPI、SDK contract 变更与审查
  - `answer-quality-gate`
    - citation、scope、retrieval、freshness、refusal、answer eval 相关实现与审查
  - `technical-writer`
    - README、API 文档、操作说明、迁移说明、运行说明
  - `doc-coauthoring`
    - RFC、技术方案、设计文档、决策文档
  - `requesting-code-review`
    - 实现完成后或提交前做代码审查
  - `systematic-debugging`
    - bug、测试失败、异常行为、集成问题排查
  - `webapp-testing`
    - Web 流程验证、E2E 自动化与 UI 调试
  - `frontend-design`
    - 页面、组件、Dashboard、交互界面生成与美化
  - `vercel-react-best-practices`
    - React/Next.js 编写、重构、性能优化
  - `web-design-guidelines`
    - UI/UX、一致性、可访问性与设计规范审查
- 任务路由建议：
  - 需求分流、版本推进、handoff/status/exec plan
    - 优先使用 `xrag-iteration-governor`
  - 按现有冻结边界推进代码实现
    - 优先使用 `implementation-lane`
  - 任何 API、schema、shared-types、状态机、OpenAPI、SDK 变更
    - 优先使用 `contract-guard`
  - 任何 citation、scope、retrieval、answer、refusal、freshness、eval 变更
    - 优先使用 `answer-quality-gate`
  - README、API 文档、操作文档、迁移说明
    - 优先使用 `technical-writer`
  - 方案文档、RFC、设计文档、决策文档
    - 优先使用 `doc-coauthoring`
  - bug、测试失败、异常行为
    - 优先使用 `systematic-debugging`
  - 实现完成、提交前、重构后审查
    - 优先使用 `requesting-code-review`
  - 浏览器流程验证、E2E、页面行为调试
    - 优先使用 `webapp-testing`
  - Web 页面实现与视觉重构
    - 优先使用 `frontend-design`
  - React/Next.js 代码编写、重构、性能优化
    - 优先使用 `vercel-react-best-practices`
  - UI/UX 审查、可访问性、一致性检查
    - 优先使用 `web-design-guidelines`
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
8. 多 lane 任务默认由主线程先冻结 `schema / shared-types / API contract / 状态机`；若涉及 AI 检索与问答，还要先冻结 `citation / scope / eval contract`
   当前 `v6 / Phase 2C` 已完成 implementation freeze，并进入 `implementation-lanes`；`Lane 0` 已完成，后续 `Lane A / B / C / D` 必须继续遵守写入边界。若环境里仍有既有旧文档停在 `not_indexed`，先单独执行 `pnpm recovery:backfill-indexing -- --dry-run` 再决定是否正式回补；`v4 / Phase 2A` 继续作为主数据模型、API 和 release 基线
9. 只有在写入边界清晰后，才把 `web / worker / upload / test` 等实现任务交给子 agent 并行
10. 子 agent 不允许擅自修改 API 路径、字段命名、状态枚举和主数据模型语义
11. 一旦并行 lane 触碰上游 contract 或集成失稳，立即切回主线程收口

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

当前仓库已经收敛上述命令，新增工程或测试资产时应继续保持这些入口可用。
