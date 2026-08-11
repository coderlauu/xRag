# 23 — 真实 Provider 准入

**What to build:** 使用合规通用 Key 对固定版本的 Chat、流式 Chat、Embedding 和 Rerank 做受费用保护的真实准入，并只登记实际通过的能力。

**Blocked by:** 10, 13, 14, 16, 22；缺少合规通用计费 Key 时保持 blocked

**Status:** partially-verified（`2026-08-07`）——Chat、流式 Chat、**Rerank** 已合规通过，
REAL-05/07 未做。**不是 blocked 了**：合规 `sk-` 通用 Key 已注入 `backend/.env`，原阻塞前提消失。

## Slice

- 执行日重新核对官方模型版本、区域、限额、价格和 quota-domain 语义。
- 真实测试由 `DASHSCOPE_API_KEY` 与 workspace/base URL 显式守门。
- 固定版本 Chat/stream、Embedding、Rerank 各做短调用，并验证 request ID 脱敏、usage、取消和协议边界。
- 每能力不超过 20 次短请求；记录日期、环境、结果与费用保护。
- 只把实际通过的能力写入 `verifiedCapabilities`；失败或无 Key 时记录阻塞，不改代码伪造支持。

## Acceptance

- [ ] REAL-01~REAL-08 完成；无合规 Key 时维持明确 blocked。→ **部分完成**，逐条见下表
- [x] Key 为通用计费凭据，不是 Coding Plan/工具 token。
- [ ] 费用告警已开启且实测调用数受限。→ **调用数受限已满足，费用告警未开启**（控制台操作，需项目所有者执行）
- [ ] 测试矩阵包含一手资料链接、执行日期和能力级结果。
- [x] 真实准入失败不回退工单 22 的假 Provider CI 门禁。→ 无 Key 时 3 条真实用例自动 skipped，全量 402 tests / 0 failures / 5 skipped

## 执行记录（`2026-08-07`）

**凭据**：百炼通用 API Key（`sk-` 开头，非 `sk-sp-` 套餐、非 Coding Plan），注入
`backend/.env` 的 `DASHSCOPE_API_KEY`。实测该 Key 在专属实例端点
`https://llm-<实例 id>.cn-beijing.maas.aliyuncs.com/compatible-mode/v1` 与公共端点
`https://dashscope.aliyuncs.com/compatible-mode/v1` 上返回**完全相同**的 236 个模型，
故 `ai-models.yml` 的 `base-url` 无需改动。

**前置修复**：`RoutingChatClient` / `RoutingStreamingChatClient` 此前没有任何 `@Configuration`
把它们暴露成 bean，`@Autowired ChatClient` 直接 `NoSuchBeanDefinitionException`——即"缺 Key"
并不是 Chat 零真实调用的唯一原因，就算当时有 Key 也调不通。已补
`com.app.ai.config.ChatClientConfiguration`（与 Embedding/Rerank 两个配置同形）。

| 用例 | 结果 | 证据 |
|---|---|---|
| REAL-01 固定版本 Chat | **通过** | 6 次成功；`actual_model_identity=qwen-plus-2025-12-01`、`provider_request_id_masked=3dce****a479`（脱敏生效）、usage 18/1 均已入库 |
| REAL-02 流式 Chat | **部分通过** | 3 次成功，事件序列 `StreamStarted → TextDelta+ → Completed`、`finish_reason=STOP`、usage 已记录。**取消行为未验**；**`provider_request_id_masked` 在流式链路为空**（同步链路有值），是待跟进的诊断缺口 |
| REAL-03 text-embedding-v3 1024 维 | **部分通过** | `/compatible-mode/v1/embeddings` 200，返回 1024 维、与 `vector(1024)` 一致。**但只验到 curl 层：阿里云 Embedding 从未走过 xrag 完整链路**（现有 6 次成功 EMBEDDING 全部走火山 `doubao-embedding-vision`）。批次上限与价格快照未按当日官方文档核对 |
| REAL-04 `qwen3-rerank` | **通过**（补适配器后）| 走 xrag 完整链路：`SUCCEEDED` / `rerank_applied=t` / `http_status=200` / `attempt_count=1`，`provider_request_id_masked=3473****c803`，排序正确（相关文档排第一）。`aliyun-qwen3-rerank-cn` 已 `enabled: true` |
| REAL-05 429 观察 | **未做** | 本次未制造或观察到限流 |
| REAL-06 凭据检查 | **通过** | 通用 `sk-` Key；未动火山 Coding Plan 的 `EMBEDDING_API_KEY`（architecture §13.2 禁止扩展用途） |
| REAL-07 费用检查 | **部分通过** | 调用量远低于上限：CHAT 6 / STREAM_CHAT 3 / RERANK 2 次链路调用，另 6 次 curl 探针；合计输出 token 个位数。**控制台费用告警未开启** |
| REAL-08 能力矩阵 | **机制已实现（`2026-08-11`）**，前端未展示 | 见下方「REAL-08 落地记录」 |

## REAL-08 落地记录（`2026-08-11`）

`DiagnosticsOverviewService.verifiedCapabilities()` 不再返回硬编码的 `List.of()`。

**数据来源**：`ai_attempt`（取 `provider_id` / `deployment_id` / `outcome`）内连接父
`ai_logical_call`（取 `capability`）——`ai_attempt` 没有 capability 列，能力名只能从父表拿。
按 (providerId, deploymentId) 归集成能力集合，再填进 overview 的每个 deployment。

**成功判据**：`ai_attempt.outcome = 'SUCCEEDED'`，即"这一次真的把请求打到了这个 deployment
并拿回合法响应"。三处刻意的选择：

1. **不用 `outcome not in ('FAILED', 'STREAM_FAILED')`**（那是总览两个 24 小时聚合的口径）。
   差集里的 `RATE_LIMITED / TIMED_OUT / CANCELED / EMPTY_STREAM` 都不是失败，但更不是"调通了"。
2. **不读 `ai_logical_call.actual_deployment_id`**。约束 `ck_ai_call_execution_summary` 规定它只在
   `contributing_deployment_count = 1` 时有值，一次跨 deployment 的 mixed execution Embedding
   即使整通成功，`actual_*` 三列也全是 null——用它统计会把这类成功调用整通漏掉。反过来，
   一通最终成功的调用里那些 FAILED 的 failover attempt 也不该记功。attempt 级判据同时解决两面。
3. **不与目录的 `capabilities` 求交集或兜底**。那份是 adapter 理论能力；"这个部署理论上支持
   RERANK"与"我们真的成功调通过它的 RERANK"是两件事，用前者顶替后者会让准入结论凭空成立。
   两列不一致本身就是要看的信息。读取失败时返回空集（fail closed）——总览的计数用 null 表达
   "读不到"是因为 0 会被读成"一切正常"，而验证记录是准入证据，缺证据时少声称一项能力是安全的。

**语义边界（新引入的，需要知道）**：诊断记录有保留窗口（ADR 0005：7 天或最近 10,000 次逻辑调用
后物理删除，`ai_attempt` 随 `on delete cascade` 一起没）。所以这个字段准确的含义是
**"在现存诊断记录里调通过"**，长期不调用的能力会自然掉出列表。它不是一张持久的准入登记表；
真要那个语义得单独建表 + 写入路径，不在本次范围。

**测试**：`DiagnosticsOverviewServiceTests.verifiedCapabilities没有成功记录时为空且不拿理论能力顶替`
（原用例改形，纪律不变）；`DiagnosticsOverviewIntegrationTests` 新增两条真库用例——
`verifiedCapabilities只登记attempt级成功的deployment而不是整通调用的参与者`
（chat-a 声明 CHAT+STREAM_CHAT 但只有 STREAM_CHAT 调通；chat-a/chat-b 在一通最终成功的调用里
失败过，只有 chat-c 记功；成功记录刻意放在 24 小时窗口外）与
`记录存在但没有attempt级成功时verifiedCapabilities为空`（覆盖 STREAM_FAILED / FAILED /
RATE_LIMITED）。三条都做了变异验证：改成理论能力兜底 → 3 条红；改成 `not in (FAILED,
STREAM_FAILED)` → RATE_LIMITED 那条红；加上 24 小时窗口 → 窗口外那条红。
`-Dtest='DiagnosticsOverviewServiceTests,DiagnosticsOverviewIntegrationTests,DiagnosticCallQueryIntegrationTests,DiagnosticPrivacyAuditIntegrationTests'`
共 30 tests / 0 failures（含隐私审计，确认新字段不带出任何秘密）。

**还差什么**：① **前端未展示**——`GET /api/v1/ai/diagnostics/overview` 现在返回真实值了，但
`frontend/src/pages/diagnostics/OverviewBar.tsx` 的候选表只渲染 `capabilities` 一列，页面上仍看不到
已验证能力。加这一列属于界面变更，按 CLAUDE.md 的产出物闸门要先动 `tech/model-scheduling/ui-spec.md`
（目前完全没提这个字段）和原型并经用户确认，本次没做。② `tech/model-scheduling/test-matrix.md`
§14.7 的 REAL-08 行仍写着"机制未实现"，按矩阵图例的证据纪律留给回写环节处理。

**Rerank 曾经的缺口与修复**：根因从来不是凭据。`OpenAiCompatibleRerankProvider` 打
`{base}/reranks`，而百炼 compatible-mode 下 `/reranks` 与单数 `/rerank` **均返回 404 且响应体为空**；
同一把 Key 打 DashScope 原生 `/api/v1/services/rerank/text-rerank/text-rerank` 返回 200 与真实
`relevance_score`（0.859 / 0.196，排序正确）。修复前走 xrag 链路的表现是
`ModelCallException`「模型调用参数无效」，诊断表记
`INVALID_REQUEST / RESPONSE_HEADERS / http_status=404 / NON_RECOVERABLE_ERROR`。
**404 被归一化成 INVALID_REQUEST 的措辞会把排查往"请求参数写错了"上带**，仍值得跟进。

修复（同日完成）：新增 `DashScopeNativeRerankProvider`，并给目录加 **deployment 级 adapter 覆盖**
（`rerank.adapter`，缺省继承 Provider 的 `adapter`）。之所以不能按 Provider 整体切换，是因为同一个
百炼 Provider 的 Chat 与 Embedding 确实是 OpenAI 兼容的，只有 Rerank 不是。协议差异见
[architecture §4.2](../../../tech/model-scheduling/architecture.md)。当前限制：`RoutedRerankClient`
按 `providerId` 解析适配器，同 Provider 下多个 RERANK deployment 必须声明同一 adapter，否则启动失败。

**测试载体**：`RealChatApiTests`（同步 + 流式，含按 `logicalCallId` 精确断言诊断落库）、
`RealRerankApiTests`、`DashScopeNativeRerankProviderTests`（本地 HTTP server 契约测试，7 条）。
前两者以 `@EnabledIfEnvironmentVariable(DASHSCOPE_API_KEY)` 守门。
**核对真实调用证据要查 `xrag_test` schema**——surefire 在 `pom.xml` 把测试数据源指到了
`currentSchema=xrag_test`，查 `public` 会得到"一条都没写"的假象。

契约测试做过变异验证：把请求体改回 OpenAI 平铺形状，只有"请求体使用 input 与 parameters 嵌套结构"
一条变红，其余 6 条仍绿。

**回归**：注入 Key 时 `mvnw verify` 409 tests / 0 failures / 0 skipped；不注入时 `ci-validate.sh`
EXIT=0 且真实用例自动跳过，工单 22 的假 Provider CI 门禁不受影响。

**剩余待办**：① 控制台开费用告警；② 按当日官方文档核对 embedding 批次上限与 rerank 文档上限
（`ai-models.yml` 的 `max-documents: 100` 仍是占位值，architecture §13.1 记的官方上限是 500）；
③ ~~`verifiedCapabilities` 的登记机制~~ 后端已做（`2026-08-11`），**前端展示未做**；
④ 阿里云 Embedding 走一次完整链路；
⑤ 流式链路补 `provider_request_id_masked`；⑥ 404 归一化成 INVALID_REQUEST 的措辞。

**Design:** [architecture §13](../../../tech/model-scheduling/architecture.md#13-真实-provider-准入)
