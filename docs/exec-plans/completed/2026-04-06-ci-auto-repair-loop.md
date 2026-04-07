# CI Auto Repair Loop

## 1. Metadata

- `plan_id`: `ci-auto-repair-loop`
- `status`: `completed`
- `owner`: `codex`
- `related_docs`: [current handoff](/Users/coderlauu/xRag/docs/handoff/current.md), [v2 status](/Users/coderlauu/xRag/docs/status/v2-phase-1b.md), [CI Failure Loop](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-04-ci-failure-loop.md), [CI 自动修复闭环方案](/Users/coderlauu/xRag/docs/process/2026-04-06-ci-auto-repair-loop.md)

## 2. Objective

在现有 `CI Failure Loop` 的基础上，实现第一版可运行的自动修复闭环：对低风险、规则型失败自动修复并创建修复 PR。

## 3. Scope

### In Scope

- 新增 `CI Auto Repair` workflow
- 复用现有 failure context 收集能力
- 实现失败分类脚本
- 实现自动修复执行脚本
- 自动创建 repair branch 和 PR
- 同步更新状态与入口文档

### Out Of Scope

- 自动 merge 到 `main`
- 自动 production deploy
- 对高风险失败做自动代码修复

## 4. Supported Repair Types

1. `openapi_contract_out_of_date`
2. `outdated_lockfile`
3. `search_page_e2e_selector_drift`

## 5. Risks

- 自动修复脚本若越界修改非目标文件，会制造新的 CI 波动
- `GITHUB_TOKEN` 权限不足会导致 branch / PR 创建失败
- 若分类规则写得过宽，会误判失败类型

## 6. Plan

1. 修当前 `e2e` 红灯，改为稳定 selector
2. 新增 `CI Auto Repair` 方案文档
3. 新增失败分类脚本
4. 新增自动修复脚本
5. 新增 GitHub workflow
6. 本地验证支持类型分类
7. 更新状态与 handoff

## 7. Validation

- `pnpm --filter @xrag/web typecheck`
- `pnpm validate`
- 脚本语法校验
- 至少验证一类支持型失败可生成修复分支和 PR

## 8. Rollback

- 若自动修复 workflow 产生噪音或误修复，可先禁用该 workflow
- 不影响当前主 `CI/CD` 与生产部署链
