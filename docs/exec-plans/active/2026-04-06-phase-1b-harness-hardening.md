# Phase 1B Harness Hardening

## 1. Metadata

- `plan_id`: `phase-1b-harness-hardening`
- `status`: `active`
- `owner`: `codex`
- `related_docs`: [current handoff](/Users/coderlauu/xRag/docs/handoff/current.md), [v2 status](/Users/coderlauu/xRag/docs/status/v2-phase-1b.md), [harness engineering playbook](/Users/coderlauu/xRag/docs/process/2026-03-31-harness-engineering-playbook.md), [Phase 1B architecture](/Users/coderlauu/xRag/tech/architecture/2026-04-06-phase-1b-architecture.md), [Phase 1B data model](/Users/coderlauu/xRag/tech/data-model/2026-04-06-phase-1b-data-model.md), [Phase 1B api design](/Users/coderlauu/xRag/tech/api/2026-04-06-phase-1b-api.md), [CI failure loop](/Users/coderlauu/xRag/docs/exec-plans/active/2026-04-04-ci-failure-loop.md)

## 2. Objective

把 `Phase 1B` 的 harness 能力从“文档约束”推进为“可机检、可回放、可恢复”的执行系统，优先解决 contract 漂移、OpenAPI/SDK 生成链、状态机回归验证和 ops / incident 工具化。

## 3. Scope

### In Scope

- 冻结并同步 `schema / shared-types / OpenAPI / api-client / docs/generated`
- 在 `CI` 中增加 contract drift 与生成产物一致性校验
- 为 `upload_status / parse_status / diagnosis_code` 建立 fixture 与 integration 回归
- 把高频 production 排障路径与 `CI failure loop` 收口为 repo 内脚本和固定入口
- 形成 `P0 / P1 / P2` 的执行顺序，作为 `Phase 1B` 后续并行实施前的工程基线

### Out Of Scope

- 直接完成 `pdf` 真实解析与 multipart 的全部业务实现
- 新增 AI 问答、OCR、语义搜索
- 引入独立 observability 平台替代当前最小基线
- 做无人确认的全自动生产修复或自动发布

## 4. Assumptions

- `v2 / Phase 1B` 仍是当前活跃版本，harness hardening 属于当前版本的工程强化项
- `Phase 1B` 真正并行实施前，必须先冻结 `contract` 与状态机语义
- 当前仓库已有可工作的 `CI -> deploy -> smoke` 与 `CI failure loop` 基线，可在其上增量演进

## 5. Risks

- 若先启动 `multipart / pdf parse / frontend diagnostics` 实现而不先补 `contract` 机械化，漂移会继续扩大
- 若一次性把所有 harness 想法都落地，会挤占 `Phase 1B` 主功能实施窗口
- 生成链与结构校验若引入方式不稳，可能短期内增加 `CI` 噪音

## 6. Plan

1. 定义 `Phase 1B` 的 contract source-of-truth 顺序，先收口 `tech docs -> schema -> shared-types -> OpenAPI -> api-client -> docs/generated`
2. 落地 `P0`：同步 `Phase 1B` contract、补 `OpenAPI` 生成与 drift check、更新 `docs/generated`
3. 落地 `P1`：补状态机 / 诊断码 fixture 与 integration coverage，补结构边界检查，补 `ops / incident` 脚本入口
4. 落地 `P2`：补 `link check`、docs freshness / consistency check、发布证据与回滚证据强化
5. 完成后再更新 `status`，确认 `Phase 1B` 已具备安全并行实施 `multipart / pdf parse / web diagnostics / ops` 的基线

### 6.1 Priority Backlog

#### `P0`

- `schema / shared-types / OpenAPI / api-client / generated assets` 与 `Phase 1B` 设计同步
- `CI` 增加 `openapi:generate`、生成产物漂移校验与失败说明
- `docs/generated/openapi/` 从 `Phase 1A` 单快照升级为当前版本真实产物

#### `P1`

- 为 `upload_status / parse_status / diagnosis_code` 增加 fixture、integration 和 retry 回归
- 为 `web -> api-client`、`controller -> service -> repo`、`worker 不承载 HTTP` 增加结构化检查
- 为 production 上传排障、文档排障、`CI` run 排障增加固定脚本入口
- 评估 incident 记忆层是否需要补 repo 内索引或聚合资产

#### `P2`

- 增加文档链接校验与 docs freshness check
- 增加 `exec plan / status / handoff / generated assets` 的一致性检查
- 为部署链补更明确的 smoke / rollback / evidence 归档

## 7. Validation

- 单元测试：为新增 harness 脚本、状态映射或校验逻辑补 targeted unit coverage
- 集成测试：覆盖 `upload complete -> verify -> queue -> parse failure / retry` 等关键状态组合，并校验诊断码与 contract 输出
- E2E / smoke：保持现有 `validate / integration / e2e:smoke` 绿灯；`P0` 完成后至少重新验证一次主链路 smoke

当前证据：

- `2026-04-06`: `pnpm validate` 已通过，包含 `lint + typecheck + contract:check`
- `2026-04-06`: `openapi:generate` 已产出 `docs/generated/openapi/phase-1b-api.json`
- `2026-04-06`: 本地 `API integration` 受 Docker daemon 不可用影响，尚未补齐

## 8. Rollback

- 若新增 harness 校验导致 `CI` 不稳定，可先按 job 级禁用新增检查，不回退主业务链
- 若生成链不稳定，可暂保留生成产物并回退自动比对步骤，但不回退已冻结的 contract 文档
- 本计划只强化工程执行系统，不改变生产产品范围；必要时可按脚本、workflow、生成产物分组回退

## 9. Decision Log

- `2026-04-06`: 将 harness hardening 归类为 `medium-feature`，纳入 `v2 / Phase 1B` 当前版本推进，不新开版本
- `2026-04-06`: 优先级确定为 `P0 contract mechanicalization > P1 evaluation and ops tooling > P2 governance polish`
- `2026-04-06`: `P0` 已完成 contract mechanicalization，新增 migration、Phase 1B OpenAPI 生成链与 drift check
