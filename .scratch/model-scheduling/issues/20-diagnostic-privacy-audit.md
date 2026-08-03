# 20 — 诊断隐私审计

**What to build:** 对四能力、持久化、诊断 API、日志和 Trace Console 实际 DOM 做统一标记串审计，证明敏感正文与凭据不会越过诊断边界。

**Blocked by:** 05, 10, 13, 14, 15, 16, 17, 18

**Status:** blocked

## Slice

- 标记串覆盖 Chat/Embedding/Rerank、工具参数、raw error、request ID 和 secret。
- 扫描诊断表、API 序列化结果、应用日志、前端状态与实际渲染 DOM。
- 验证 request ID 在入库前已脱敏，列表、详情和 DOM 使用相同脱敏值。
- 覆盖成功、失败、切换、流中断、mixed Embedding 与 Rerank Noop 场景。
- 本工单只做隐私契约与自动化审计，不注入 writer/队列/进程故障。

## Acceptance

- [ ] DIA-10~DIA-12、UI-08 与 AC-15~AC-16 有自动化/浏览器证据。
- [ ] 所有持久化/API/日志/页面 DOM 均找不到隐私标记串。
- [ ] 完整 Provider request ID、Authorization、secret property 解析值和 raw body 均不可见。
- [ ] 后端 verify、前端测试/build/lint 通过。
- [ ] 失败报告只指出泄漏位置和字段，不在报告中复制敏感原文。

**Design:** [data model §8](../../../tech/model-scheduling/data-model.md#8-隐私与完整性检查)
