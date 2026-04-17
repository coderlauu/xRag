# Phase 2C Testing And Release Readiness

## 1. Metadata

- `plan_id`: `phase-2c-testing-and-release-readiness`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [v6 handoff](/Users/coderlauu/xRag/docs/handoff/v6.md), [v6 status](/Users/coderlauu/xRag/docs/status/v6-phase-2c.md), [current handoff](/Users/coderlauu/xRag/docs/handoff/current.md), [Phase 2C contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-contract-freeze.md), [Phase 2C implementation lanes](/Users/coderlauu/xRag/docs/exec-plans/completed/2026-04-16-phase-2c-implementation-lanes.md)

## 2. Objective

在 `Lane 0 / A / B / C / D` 已全部落地后，把 `Phase 2C / P0` 从“实现与测试 lane 完成”推进到“测试与发布准备完成”，补齐当前 HEAD 的验证证据、CI 结论确认与文档收口。

## 3. Preconditions

当前已满足的前提：

1. `schema / migrations / shared-types / DTO / OpenAPI / SDK / web api adapter` 已冻结到代码事实源
2. `api` 已完成 `/ops/overview`、`/ops/trends`、legacy ops endpoints 与事实写入读模型
3. `web` 已完成治理主板与 prompt-only notices
4. `Lane D` 已在本地通过 `pnpm test:integration` 与 `./scripts/run-e2e-smoke.sh`

本计划不重新打开 contract source-of-truth，仅补测试证据、CI 结论与 release-ready 文档收口。

## 4. Workstreams

### Release Evidence

- 目标：确认当前 HEAD 的本地验证集合完整，且没有遗漏必须的 gate
- 写入范围：
  - `docs/status/v6-phase-2c.md`
  - `docs/handoff/current.md`
  - `docs/handoff/v6.md`
  - `docs/exec-plans/**`
- 最低验收：
  - `git diff --check`
  - `pnpm docs:check`
  - `pnpm test:integration`
  - `pnpm test:e2e`
  - `pnpm e2e:smoke`

### CI Confirmation

- 目标：确认当前 main HEAD 的 GitHub Actions 结论，避免只靠本地验证宣布完成
- 写入范围：
  - `docs/status/v6-phase-2c.md`
  - `docs/handoff/v6.md`
- 最低验收：
  - 当前 main HEAD 至少一轮 validate / integration / e2e / smoke 绿灯，或在文档中明确记录待确认状态与原因
- `2026-04-17` 更新：GitHub Actions run `24542756511` 已失败，失败点为 `smoke-production` 的 `Persist production deployment record`；根因是 job 未执行 `pnpm install --frozen-lockfile` 就调用了依赖 `pg` 的 `scripts/write-deployment-record.mjs`

### Completion Or Handoff

- 目标：在证据足够时把 `Phase 2C` 切到 completed；若仍缺 CI 证据，则保留 active 但让新 session 可以直接恢复
- 写入范围：
  - `docs/status/v6-phase-2c.md`
  - `docs/handoff/current.md`
  - `docs/handoff/v6.md`
  - `docs/exec-plans/active/*`
  - `docs/exec-plans/completed/*`
- 最低验收：
  - 活跃 exec plan 与实际阶段一致
  - status / handoff 明确写出下一步与剩余证据
  - 新 session 不依赖聊天历史即可继续

## 5. Validation

- `git diff --check`
- `pnpm docs:check`
- `pnpm test:integration`
- `pnpm test:e2e`
- `pnpm e2e:smoke`

如果当前环境无法确认 GitHub Actions run，必须在 status 中写清楚“本地已验证，CI 结论待确认”。

当前本地已确认：

- `2026-04-17`: `git diff --check`
- `2026-04-17`: `pnpm docs:check`
- `2026-04-17`: `pnpm test:integration`
- `2026-04-17`: `./scripts/run-e2e-smoke.sh`

## 6. Risks

- 若当前 HEAD 的 CI 结论未确认，就直接宣布 `Phase 2C` 完成，后续回归会缺少主线证据
- 若 active exec plan 仍停留在 implementation lanes，新 session 容易重复进入已完成 lane
- 若把 prompt-only notices 改成强拦截来适配测试，会破坏 `Lane C` 已冻结的行为边界

## 7. Exit Criteria

当以下条件满足时，本计划可视为完成：

1. `Phase 2C / P0` 的本地 integration、e2e、smoke 证据已补齐
2. 当前 HEAD 的 CI 结论已确认，或 blocker 已被明确记录
3. `status / handoff / exec plan` 已切到 completed 或明确的待确认态
4. 当前 repo 可在无聊天历史的情况下 resume

## 8. Decision Log

- `2026-04-17`: `Lane D` 已完成本地测试收口；`phase-2a-p0` 的 `/ops` e2e 断言已对齐到新治理主板
- `2026-04-17`: `implementation-lanes` 计划退出条件已满足，改由独立 `testing-and-release-readiness` active exec plan 接手
- `2026-04-17`: GitHub Actions run `24542756511` failed in `smoke-production` because deployment record persistence executed without workspace dependencies; next step is to fix the workflow and rerun main CI
