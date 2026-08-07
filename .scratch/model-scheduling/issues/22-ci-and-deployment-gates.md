# 22 — CI 与部署门禁

**What to build:** 把非敏感模型配置、秘密注入、可编程假 Provider 和 Phase 2 自动化验证接入部署文档与 CI；不调用真实收费 Provider。

**Blocked by:** 01~21

**Status:** done（2026-08-07）

## Slice

- env examples 登记 Provider、deadline、attempt、breaker、stream buffer 和 retention，仅使用 secret property 名。
- CI 强制使用 `ProgrammableFakeProvider`，不读取任何收费 Key。
- 后端 verify、前端 build/lint、文档链接和整体 validate 接入门禁。
- 部署 README 说明单实例 breaker、配置重启生效、诊断 best-effort 和真实准入的独立步骤。
- 远端 CI 结果与 Phase 1 既有递延项分开记录。

## Acceptance

- [x] env example 只出现占位值与 secret-property 名；`.github/workflows/ci.yml` 全文不含 `DASHSCOPE_API_KEY` / `EMBEDDING_API_KEY`，CI 不注入任何 Provider 密钥。
- [x] 无收费 Key 的普通 CI 可完整执行 Phase 2 自动化：全部用 `ProgrammableFakeProvider`，`ai-models.yml` 里唯一的 RERANK deployment 默认 `enabled: false`，不会因缺密钥变成配置故障。
- [x] `scripts/ci-validate.sh` 本地全绿（clean 构建后 backend 399 tests，0 failures，2 skipped）。
- [x] 本工单不写 `verifiedCapabilities` 的真实通过结论——它仍恒为空数组。
- [x] 真实 Provider 未准入既不伪装成 CI 失败也不伪装成通过：deploy/README 新增一节写明"CI 绿灯不代表真实准入通过"，工单 23 保持 blocked。

**Design:** [implementation lanes §5 Lane 运维](../../../docs/exec-plans/completed/2026-07-28-model-scheduling-implementation-lanes.md#lane-运维)


## 实现记录（2026-08-07）

**清掉了一批死配置。** `deploy/env/{production,staging}.env.example` 里还留着
`EMBEDDING_BASE_URL` / `EMBEDDING_MODEL` / `EMBEDDING_DIMENSIONS` / `EMBEDDING_BATCH_SIZE`
四个变量，而工单 15 早已把这些属性从后端删掉——`grep` 确认 `backend/src/main` 一处引用都没有。
照着它们配置的人会以为自己在调模型，其实什么都没发生。已删除，换成真实生效的 Phase 2 键。

env example 现在登记：Provider 密钥变量名（与 `ai-models.yml` 的 secret-property 对应）、
Embedding 节流间隔、默认向量身份、诊断保留窗口四项。**非敏感的模型目录仍然只在
`ai-models.yml`**，env 只放密钥值。

`deploy/README.md` 新增「模型调度引擎（Phase 2）运维须知」，写清四条设计决定而非缺陷的行为：
熔断只在当前 JVM 且假定单实例（多实例下各算各的、诊断页显示的是你连到的那个实例）；
模型配置改完必须重启（catalog 是启动时快照，无热更新）；诊断 best effort 意味着页面可能
缺记录，看到"部分调用诊断可能缺失"要查数据库而不是 Provider，且多实例部署应只让一个实例
开启保留清理；真实 Provider 准入是独立步骤，CI 绿灯不代表它通过。

**未做（明确留给后续决策）：** 前端测试运行器。仓库从无前端测试，工单 18 尝试引入 vitest 时被
pnpm store 版本不一致挡住（`node_modules` 由 10.7.1 装出、当前环境 10.12.3，需整体重装依赖）。
这会改变所有人的本地环境，不该由一张 CI 工单单方面决定；它一天不落地，UI-04/05/08/09/10 与
工单 20 的页面 DOM 扫描就只有浏览器实测证据、没有可重放的自动化。建议单开一张工单，
内容是：统一 pnpm 版本 → 重装依赖 → 引入 vitest + testing-library → 把 `pnpm test`
接进 `scripts/ci-validate.sh` 与 CI。

> **已完成（2026-08-07）**，见 [test-matrix §14.6](../../../tech/model-scheduling/test-matrix.md)。
> 实际成因是 pnpm **store 目录版本**（v11 vs v10）而非 pnpm 版本号，详见工单 18 的更正。
> CI workflow 无需改动——所有门禁都经 `scripts/ci-validate.sh` 这一个入口。
