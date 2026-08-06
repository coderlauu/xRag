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

## Decisions（2026-08-04 与用户确认，实现前已定）

- **`retention` 的两个配置项由本工单引入**（`maxAgeDays` / `maxLogicalCalls`），工单 19 复用同一份配置，不各自定义。`oldestAvailableAt` 取自 `ai_logical_call` 的最早 `started_at`。
- **`verifiedCapabilities` 先返回空数组**，并在响应契约与本工单里注明"待工单 23 真实 Provider 准入补齐验证记录后才有值"。**禁止用 `capabilities` 顶替**——规格明确要求它只来自明确配置和真实验证记录，不把 adapter 理论能力当已验证。
- **`limits.maxContextTokens` 本期不返回**：`ModelDeployment` 上没有这个字段，为了一个示例字段扩模型不值得；需要时另开工单。

## Notes

- 数据来源已全部就位，无需新建基础设施：`ModelCatalogSnapshot`、`ModelBreakerRegistry.snapshots()`（已含 state / allowsCall / halfOpenPermissionInUse / lastChangedAt / lastChangeReason 与四个计数）、`DiagnosticRecorder.droppedCount()` / `writeFailureCount()` 均已是 Spring bean。只需新写 `ai_logical_call` 的 24 小时聚合与最早时间查询。
- CFG-10 应断言**序列化后的响应文本**不含密钥值与 Authorization，而不是只检查 DTO 字段：`ModelProvider.secretProperty()` 是属性名而非密钥值，但 DTO 一旦整体嵌入 `ModelProvider` 就会把它带出去。
