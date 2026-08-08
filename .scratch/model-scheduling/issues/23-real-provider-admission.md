# 23 — 真实 Provider 准入

**What to build:** 使用合规通用 Key 对固定版本的 Chat、流式 Chat、Embedding 和 Rerank 做受费用保护的真实准入，并只登记实际通过的能力。

**Blocked by:** 10, 13, 14, 16, 22；缺少合规通用计费 Key 时保持 blocked

**Status:** blocked

## Slice

- 执行日重新核对官方模型版本、区域、限额、价格和 quota-domain 语义。
- 真实测试由 `DASHSCOPE_API_KEY` 与 workspace/base URL 显式守门。
- 固定版本 Chat/stream、Embedding、Rerank 各做短调用，并验证 request ID 脱敏、usage、取消和协议边界。
- 每能力不超过 20 次短请求；记录日期、环境、结果与费用保护。
- 只把实际通过的能力写入 `verifiedCapabilities`；失败或无 Key 时记录阻塞，不改代码伪造支持。

## Acceptance

- [ ] REAL-01~REAL-08 完成；无合规 Key 时维持明确 blocked。
- [ ] Key 为通用计费凭据，不是 Coding Plan/工具 token。
- [ ] 费用告警已开启且实测调用数受限。
- [ ] 测试矩阵包含一手资料链接、执行日期和能力级结果。
- [ ] 真实准入失败不回退工单 22 的假 Provider CI 门禁。

**Design:** [architecture §13](../../../tech/model-scheduling/architecture.md#13-真实-provider-准入)
