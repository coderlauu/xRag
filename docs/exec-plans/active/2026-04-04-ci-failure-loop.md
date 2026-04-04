# CI Failure Loop

## 1. Metadata

- `plan_id`: `ci-failure-loop`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [current handoff](/Users/coderlauu/xRag/docs/handoff/current.md), [v2 status](/Users/coderlauu/xRag/docs/status/v2-phase-1b.md), [Phase 1B PRD](/Users/coderlauu/xRag/docs/prd/2026-04-02-xrag-phase-1b-prd.md)

## 2. Objective

建立 `GitHub Actions` 失败后的标准化闭环：自动发现失败、自动收集上下文、自动生成 incident / repair artifact，再进入修复、验证和重新发布。

## 3. Scope

### In Scope

- 监听 `main` 分支 `CI` 失败
- 收集失败 run / job / step / logs / sha / workflow metadata
- 生成统一的 incident artifact
- 更新当前版本状态或关联执行计划
- 为后续 agent 修复和人工确认发布提供固定入口

### Out Of Scope

- 无人工确认的全自动生产发布
- 无边界的自动代码修复
- 非 CI 类运行时告警平台替代品

## 4. Assumptions

- 当前仓库已有稳定的 `CI -> build-images -> deploy-production -> smoke-production` 基线
- 失败闭环的第一版应以“自动归档上下文 + 人工确认修复”为主，而不是完全自治

## 5. Risks

- 若直接做全自动修复并发布，生产风险过高
- 若失败上下文收集不全，agent 仍需回头手工翻日志
- 若 incident 资产不进入 repo，后续仍会依赖聊天上下文

## 6. Plan

1. 定义失败事件最小上下文模型
2. 设计 `workflow_run` 或等价机制的 failure collector
3. 落地 incident artifact 与状态更新规则
4. 再决定是否增加 agent 自动修复入口

## 7. Validation

- 单元测试：后续按脚本实现情况补
- 集成测试：至少验证一次失败 run 能生成完整 incident 上下文
- Smoke：验证一次“失败 -> 记录 -> 修复 -> 重跑成功”的人工确认闭环

## 8. Rollback

- 第一版仅新增失败归档和流程入口，不改线上业务行为
- 若 collector 影响 `CI` 稳定性，可先禁用该 workflow，不影响主发布链

## 9. Decision Log

- `2026-04-04`: 不做无确认的全自动生产闭环，先做半自动 incident / repair loop
