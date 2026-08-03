# 22 — CI 与部署门禁

**What to build:** 把非敏感模型配置、秘密注入、可编程假 Provider 和 Phase 2 自动化验证接入部署文档与 CI；不调用真实收费 Provider。

**Blocked by:** 01~21

**Status:** blocked

## Slice

- env examples 登记 Provider、deadline、attempt、breaker、stream buffer 和 retention，仅使用 secret property 名。
- CI 强制使用 `ProgrammableFakeProvider`，不读取任何收费 Key。
- 后端 verify、前端 build/lint、文档链接和整体 validate 接入门禁。
- 部署 README 说明单实例 breaker、配置重启生效、诊断 best-effort 和真实准入的独立步骤。
- 远端 CI 结果与 Phase 1 既有递延项分开记录。

## Acceptance

- [ ] env example 和 CI 日志不含真实秘密。
- [ ] 无网络、无收费 Key 的普通 CI 可完整执行 Phase 2 自动化。
- [ ] `scripts/ci-validate.sh` 本地全绿。
- [ ] 本工单不写 `verifiedCapabilities` 的真实通过结论。
- [ ] 真实 Provider 未准入不伪装成 CI 失败或通过。

**Design:** [implementation lanes §5 Lane 运维](../../../docs/exec-plans/active/2026-07-28-model-scheduling-implementation-lanes.md#lane-运维)
