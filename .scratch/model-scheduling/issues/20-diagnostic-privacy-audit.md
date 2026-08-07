# 20 — 诊断隐私审计

**What to build:** 对四能力、持久化、诊断 API、日志和 Trace Console 实际 DOM 做统一标记串审计，证明敏感正文与凭据不会越过诊断边界。

**Blocked by:** 05, 10, 13, 14, 15, 16, 17, 18

**Status:** done（2026-08-07）

## Slice

- 标记串覆盖 Chat/Embedding/Rerank、工具参数、raw error、request ID 和 secret。
- 扫描诊断表、API 序列化结果、应用日志、前端状态与实际渲染 DOM。
- 验证 request ID 在入库前已脱敏，列表、详情和 DOM 使用相同脱敏值。
- 覆盖成功、失败、切换、流中断、mixed Embedding 与 Rerank Noop 场景。
- 本工单只做隐私契约与自动化审计，不注入 writer/队列/进程故障。

## Acceptance

- [x] DIA-10：八类标记串（Prompt、工具参数、Embedding 正文、Rerank query/候选、原始错误体、密钥值、完整 request ID）跑完四个能力后，在五张诊断表与两个诊断 API 的序列化结果里都找不到。
- [x] DIA-11：原始错误体只留下 `SERVER_ERROR + RESPONSE_HEADERS + 500`，异常消息本身也不含标记串。
- [x] DIA-12：`provider_request_id_masked` 入库即为脱敏值，列表与详情用同一个值，attempt 层一致。
- [x] UI-08：浏览器实测页面文本含脱敏 `req_****1b7c`，不含密钥/Authorization/Prompt/正文（证据见工单 18）。
- [x] 失败报告只给表名、字段名与类别名，不复制敏感原文——并由一个自检用例断言这一点。
- [x] `./scripts/ci-validate.sh` 通过（clean 构建后 backend 399 tests，0 failures，2 skipped）。
- [ ] **页面 DOM 的自动化扫描未落地**：需要前端测试运行器，见工单 18 的同一条遗留，已并入工单 22。

**Design:** [data model §8](../../../tech/model-scheduling/data-model.md#8-隐私与完整性检查)


## 实现记录（2026-08-07）

`DiagnosticPrivacyAuditIntegrationTests`。两条设计选择值得记下：

- **用扫描而不是逐字段断言。** 泄漏最常发生在**新增字段**时——有人给 DTO 加一个"顺手带上原始
  响应"的字段，逐字段断言不会失败，扫描会。表的列清单从 `information_schema` 现取，
  所以将来新增的文本列自动纳入审计范围。
- **审计自身要可信。** 一个永远找不到东西的扫描器和一个正确的扫描器，测试结果长得一模一样。
  因此加了一个自检用例：先确认干净，再人为往 `ai_logical_call.request_id` 与
  `ai_attempt.provider_request_id_masked` 各种一个标记串，断言两处都被扫出来，
  并且报告里不含原文。没有这一例，前三个用例的"通过"是没有意义的。
