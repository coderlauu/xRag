# Phase 3A Technical Evaluation

## 1. Metadata

- `plan_id`: `phase-3a-technical-evaluation`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [v7 handoff](/Users/coderlauu/xRag/docs/handoff/v7.md), [v7 status](/Users/coderlauu/xRag/docs/status/v7-phase-3a.md), [Phase 3A PRD](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-prd.md), [Phase 3A backlog](/Users/coderlauu/xRag/docs/prd/2026-04-17-xrag-phase-3a-backlog.md), [v7 interaction delta](/Users/coderlauu/xRag/design/spec/2026-04-17-v7-interaction-delta.md), [Phase 3A tradeoffs](/Users/coderlauu/xRag/docs/decisions/2026-04-17-phase-3a-p0-technical-tradeoffs.md), [Phase 3A architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-17-phase-3a-architecture.md), [Phase 3A data model](/Users/coderlauu/xRag/tech/data-model/2026-04-17-phase-3a-data-model.md), [Phase 3A api design](/Users/coderlauu/xRag/tech/api/2026-04-17-phase-3a-api.md), [Phase 3A contract freeze prerequisites](/Users/coderlauu/xRag/docs/decisions/2026-04-17-phase-3a-contract-freeze-prerequisites.md), [v6 handoff](/Users/coderlauu/xRag/docs/handoff/v6.md), [Phase 2C contract freeze](/Users/coderlauu/xRag/tech/architecture/2026-04-16-phase-2c-contract-freeze.md)

## 2. Objective

在 `v7 / Phase 3A` 已完成产品范围冻结后，对 `diagnostic sample / answer session replay / document pipeline replay / deployment compare` 进行正式技术评估，明确现有事实源可复用边界、新增 read model 方向，以及是否可以进入 `contract-freeze`。

## 3. Scope

### In Scope

- 对照当前已落地的 `answers / documents / ops` 代码与 `Phase 2C` 治理基线，确认 `Phase 3A` 是增量诊断版本，而不是第二套观测平台
- 产出 `architecture / data-model / api / contract-freeze-prerequisites` 第一版技术评估文档
- 收敛 `diagnostic sample` 的对象语义、回放资源族和 deployment compare 的边界
- 更新 `handoff / status / current / AGENTS`，保证新 session 可以直接恢复到 `technical-evaluation`

### Out Of Scope

- 直接修改 schema、shared-types、OpenAPI 或生产代码
- 直接进入 `implementation-freeze` 或 `implementation-lanes`
- 自动 remediation、自动回滚、自动重建索引
- 把 `Ask / Search / Detail` 重新做成内部诊断主入口

## 4. Assumptions

- `planning-and-scope` 已完成，第一版 `PRD / backlog / interaction delta / technical tradeoff` 已落盘
- `v6 / Phase 2C` closeout baseline 仍是 GitHub Actions run `24543526168 success`
- 当前代码基线已存在：
  - `GET /api/v1/answers/:sessionId`
  - `GET /api/v1/answers/:sessionId/retrieval`
  - `GET /api/v1/documents/:documentId`
  - `GET /api/v1/documents/:documentId/evidence`
  - `GET /api/v1/documents/:documentId/timeline`
  - `GET /api/v1/ops/overview`
  - `GET /api/v1/ops/trends`
- 当前阶段主要输出仍是文档与冻结判断，不是实现代码

## 5. Risks

- 若继续让前端拼接现有 answer/document/ops 读面，`Phase 3A` 会重复 `Phase 2C` 之前的前端 heuristics 问题
- 若为了诊断便利新造影子 explain 数据，样本回放会与正式事实链分叉
- 若 deployment compare 没有固定 anchor 和窗口定义，页面很容易把噪音波动误读为新回归
- 若此时就带入 runbook 联动、协作、自动修复，版本会再次范围膨胀

## 6. Plan

1. 审计当前 `answers / documents / ops / evaluation / deployment` 的已实现事实源，确认 `Phase 3A / P0` 的真实复用面。
2. 形成 `architecture / data-model / api / contract-freeze-prerequisites` 第一版技术评估文档。
3. 把 `v7` 当前恢复入口从 `planning-and-scope` 切换到 `technical-evaluation`。
4. 在文档层回答“是否可以进入 `contract-freeze`”，但不在本阶段提前修改运行时代码。

## 7. Validation

- 单元测试：不适用，当前为文档与技术评估变更
- 集成测试：不适用，当前不改运行时代码
- E2E / smoke：不适用，当前不改运行时代码
- 文档阶段至少保证 `pnpm docs:check` 与 `git diff --check` 通过

## 8. Rollback

- 若技术评估发现 `diagnostic sample / replay / deployment compare` 不能在现有事实源上成立，则回退到 `planning-and-scope`，重新缩减 `P0`，而不是直接扩 schema 或并行实现

## 9. Decision Log

- `2026-04-17`: `planning-and-scope` 已完成，`v7` 当前节点切换到 `technical-evaluation`
- `2026-04-17`: 当前代码基线已确认存在 `answers / documents / ops` 的可复用读面，`Phase 3A` 应优先沿用这些读面构造新诊断对象
- `2026-04-17`: 当前技术评估以“优先新增 read model、默认不新增 durable core table”为初始结论
