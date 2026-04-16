# Exec Plan Template

## 1. Metadata

- `plan_id`: `phase-2c-planning-and-scope`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: `docs/handoff/v6.md`, `docs/status/v6-phase-2c.md`

## 2. Objective

在 `Phase 2B` 已完成的可信问答基线上，为 `v6 / Phase 2C` 形成清晰的问题定义、优先级和版本边界，并完成是否进入 `technical-evaluation` 的判断。

## 3. Scope

### In Scope

- 基于 `v5` 已归档文档和 backlog 梳理下一版本候选方向
- 明确 `v6` 的问题定义、用户价值与验收标准
- 产出 `PRD / backlog / interaction delta / technical tradeoff` 第一版
- 更新 `handoff / status / current / AGENTS`，让新版本恢复入口稳定

### Out Of Scope

- 直接进入代码实现、schema 设计或 API contract 变更
- 把目标环境回补或数据修复默认提升为新版本产品目标
- 重新打开 `Phase 2B` 已完成实现 lane

## 4. Assumptions

- `v5 / Phase 2B` 已完成并归档，且 GitHub Actions run `24485981323` 已维持切版后的 main 绿态
- `Phase 2A / 2B` 已建立可信问答的核心信任边界，本轮只能在此基础上扩展
- 当前还没有经过确认的 `v6 P0`，因此先做规划和取舍，而不是技术实现

## 5. Risks

- 若直接从历史 backlog 里挑功能，会把 `v6` 重新拉回范围膨胀
- 若没有先冻结问题定义，就进入技术评估，后续 contract freeze 很容易返工
- 若把生产运行态问题和产品版本目标混在一起，版本边界会失真

## 6. Plan

1. 审核 `v5` 已完成事实、剩余 backlog 与运行态输入，整理下一版本候选问题。
2. 形成 `v6` 的 `PRD / backlog / interaction delta / technical tradeoff` 第一版。
3. 根据边界稳定度决定是否切换到 `technical-evaluation`，并同步更新 `status / current / AGENTS`。

## 7. Validation

- 单元测试：本阶段不涉及代码实现
- 集成测试：本阶段不涉及代码实现
- E2E / smoke：本阶段不涉及代码实现；文档阶段至少保证 `pnpm docs:check` 通过

## 8. Rollback

- 若 `v6` 问题定义无法成立，保持 `v6` 为 `blocked` 或重新归档，必要时把 `current.md` 指回最近完成版本

## 9. Decision Log

- `2026-04-16`: 将“进入下一版本”归类为 `new-version`，创建 `v6 / Phase 2C` handoff、status 与 active exec plan
- `2026-04-16`: `v6` 脚手架补全文档后，`pnpm docs:check` 已通过
- `2026-04-16`: 已完成 `Phase 2C` 第一版 `PRD / backlog / interaction delta / technical tradeoff`，并切换到 `technical-evaluation`
