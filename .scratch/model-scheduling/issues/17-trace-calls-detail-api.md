# 17 — Trace calls/detail API

**What to build:** 实现最近调用分页、筛选和详情 API，直接返回持久化的候选、route event、attempt、failure phase 与能力专用执行事实。

**Blocked by:** 04, 10, 13, 14, 15, 16

**Status:** done（2026-08-07）

## Slice

- 实现 calls 默认查询、分页、时间窗口和稳定排序。
- 支持 capability、多个 outcome、tier、provider、request ID 和 `failoverOccurred` 精确筛选。
- detail 按真实顺序返回 candidate → route event/attempt 时间线，不从 attempt 间隙推测 breaker 或预算事件。
- Stream 返回首事件/首有效/提交/终止阶段；Embedding 返回由成功 attempts 派生的 ordered contribution view 和 mixed/count 摘要；Rerank 返回 Noop 事实。
- 不修改 React 页面，不加入正文、raw error、完整 Provider request ID 或 secret。

## Acceptance

- [x] API-02~API-11 自动化（14 例）。API-09 严格匹配 `failoverCount>0/=0`；API-10 同字段 OR 且与其他字段 AND；API-11 分两例分别锁定"失败的首选 Provider"与"mixed Embedding 贡献者"，都通过 `ai_attempt exists` 命中而不看调用级 `actual_*`。
- [x] DIA-21：`ModelErrorType + FailurePhase + TerminationReason` 在 call 与 attempt 两层原样往返。
- [x] mixed Embedding 能从 API 精确还原：`batches` 按持久化 attempt 的 `workUnitIndex + inputFrom + inputTo` 分组，贡献者取每组最终成功的 attempt。
- [x] 不存在与已清理的 call 返回同一个稳定 404（区分开等于告诉外部"这个 ID 曾经存在过"）。
- [x] 列表与详情的执行事实一致：两处的 `lastAttemptTarget` 都只是最后一次真实 attempt，不参与 actual/mixed/contributing 语义。
- [x] `./scripts/ci-validate.sh` 通过（clean 构建后 backend 389 tests，0 failures，2 skipped）。

**Design:** [API §6~8](../../../tech/model-scheduling/api.md#6-最近调用)

## 实现记录（2026-08-07）

`com.app.ai.web` 新增 `AiDiagnosticCall`（列表项）、`AiDiagnosticCallDetail`（详情，含四能力联合结构）、
`DiagnosticCallQuery`（筛选条件）、`DiagnosticCallQueryService`（JDBC）、`DiagnosticCallsController`。

- **前置重构：把 `ApiException` / `ApiExceptionHandler` / `PageResponse` 从 `com.app.knowledge.web`
  移到 `com.app.web`。** `knowledge.web` 已经依赖 `com.app.ai.model`（handler 映射 `ModelCallException`），
  若再让 `ai.web` 反向 import `knowledge.web`，包依赖就成环了。这三个类本来就是全应用契约——
  `ApiExceptionHandler` 的注释自己写着"不能限定 basePackages"——放在 knowledge 下只是历史位置。
  纯位置迁移，改动全是 import。
- **`providerId` 与脱敏 request ID 走 `ai_attempt exists`，不比对调用级 `actual_*`。**
  否则失败的首选 Provider（actual 是切换后的那个）和 mixed Embedding（actual 三字段为 null）
  都会从筛选结果里消失。API-11 的两个用例分别把这两种情况钉死。
- **`requestId` 一律精确匹配。** 大表 `%like%` 既慢又等于开放标识枚举。
- **详情用显式 RowMapper 而不是 `queryForMap`。** 后者对 timestamptz 返回 `java.sql.Timestamp`，
  强转 `OffsetDateTime` 会在运行时 ClassCastException——实测踩到过。
- **`lastAttemptTarget` 与 `actual*` 严格分开。** 前者只是最后一次真实 outbound attempt 的摘要，
  没有 attempt 时为 null；把它当"实际路由"显示会把一次全部失败的调用画成成功打到了某个模型。

**已知规格缺口（需要产品/技术方案裁决，本工单未擅自补）：** api.md §7 的 Embedding
`capabilityDetails.validation` 承诺 `countAndOrderValid / dimensionsValid / finiteValues` 三个标志，
但 V6 Schema 没有存它们，失败调用也无法把 `PROTOCOL_CONTRACT + PROVIDER_RESPONSE_VALIDATION`
归因到具体是哪一项没过。当前实现：调用成功时三项返回 true，其余情况整个 `validation` 返回 null，
**不猜**。要真正兑现契约需要 V7 加三列（或一个校验结果枚举），属于另开工单的范围。
