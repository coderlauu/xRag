# 05 — 只读调度总览 API

**What to build:** 从 catalog、breaker registry 和 writer 计数组装 `GET /api/v1/ai/diagnostics/overview`；本工单不修改前端。

**Blocked by:** 01, 04, 09

**Status:** ready

## Slice

- 返回 deployment 的 enabled/configured/verified capabilities 与当前 breaker 状态。
- 返回诊断 dropped/write-failure 计数及 registry 重建语义。
- 无 Provider、缺 secret、配置禁用时返回稳定空/状态对象而非 500。
- DTO 与序列化层禁止 secret、完整 Provider request ID、正文和原始错误。
- 只实现 overview，不实现 calls 列表/detail 或 React。

## Acceptance

- [ ] API-01 与 CFG-10 自动化。
- [ ] 无 Provider、UNCONFIGURED、DISABLED、breaker OPEN/HALF_OPEN 状态可构造。
- [ ] 输出无操作型 CRUD/Key/手动 breaker 字段。
- [ ] writer 计数读取失败不会伪造为零。
- [ ] `./mvnw -B verify` 通过。

**Design:** [API §5](../../../tech/model-scheduling/api.md#5-调度总览)
