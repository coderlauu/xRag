# P01 — ServiceTier Chat 采样参数端口

**What to build:** 按 [ADR 0006](../../../docs/adr/0006-bind-chat-sampling-to-service-tier.md) 为
`com.app.ai` 补齐 catalog 驱动的 Chat 采样参数；业务调用方仍只传 `ServiceTier`，同步与流式 Provider
请求显式携带所选 deployment/tier 的 `temperature` 与 `topP`。

**Blocked by:** 无（已与 Phase 2 工单 23 留下的 Rerank adapter 改动逐项合并，未覆盖其产物）

**Status:** done（2026-08-07）

## Slice

- 新增不可变 `ChatProfile` / `ChatSamplingParameters`，由 `ModelDeployment` 持有按 `ServiceTier`
  索引的采样配置；不修改 `ChatCommand` 和 `CallOptions`。
- 为 `AiModelsProperties` 与 `ModelCatalogFactory` 增加 `chat.sampling-by-tier` 绑定和启动校验：仅 Chat
  deployment 可配置、键与 tiers 完全一致、`temperature` 为 `[0,2]`、`top-p` 为 `(0,1]`。
- 把采样配置以稳定顺序纳入 config fingerprint；密钥值仍不得进入 fingerprint。
- 扩展 `ProviderChatRequest`，让 `SingleCandidateChatClient`、`RoutingChatClient` 和流式
  `CandidateStreamAttempt` 在每个 attempt 上按目标 deployment 与命令 tier 解析参数。
- 让 `OpenAiCompatibleChatProvider` 的同步与流式请求体都发送 `temperature` / `top_p`；
  `ProgrammableFakeProvider` 保留完整请求供契约测试断言。
- 在 `ai-models.yml` 为现有 `aliyun-qwen-plus-cn` 的 `BALANCED` tier 补显式参数；不在本工单新增
  `ECONOMY` / `QUALITY` 路由，也不改问答业务代码。
- 更新受影响的模型目录、同步 Chat、路由 Chat、流式 Chat 和 Provider adapter 测试；机械迁移测试构造器
  时不顺手重构邻近代码。

## Acceptance

- [x] 业务侧仍无法直接传 `temperature`、`topP`、`thinking` 或供应商透传 Map。
- [x] Chat deployment 缺 profile、tier 键不一致、数值越界、非 Chat deployment 配 profile 均在启动
  解析阶段失败；错误路径指向具体 catalog 字段。
- [x] 同步、流式、单候选、重试和跨候选故障转移均使用实际目标 deployment 的同 tier 参数。
- [x] OpenAI-compatible 同步与流式请求体都包含 `temperature` 和 `top_p`，且工具请求行为不回归。
- [x] 只改采样值会改变 config fingerprint；配置顺序变化不会改变 fingerprint；secret 值仍不影响它。
- [x] 诊断继续以 `serviceTier + policyVersion + configFingerprint` 追溯配置，不新增逐调用正文或采样值列。
- [x] `thinking` 仍不存在于 `ChatCommand`、`ProviderChatRequest` 与 `ChatStreamEvent`。
- [x] `./mvnw -B verify` 与 `node scripts/check-doc-links.mjs` 通过。

## Completion evidence

- `ModelDeployment.chat` 持有不可变按 tier profile；三个同步/流式 dispatch 点在每次 attempt 从目标 deployment 解析。
- OpenAI-compatible 同步与流式 adapter 都发送 `temperature/top_p`；故障转移测试断言不同 deployment 使用各自值。
- 与 DashScope 原生 Rerank 改动合并后，AI 定向测试通过；全量 `./mvnw -q verify` 通过：
  **415 tests，0 failures，0 errors，5 skipped**。
- `node scripts/check-doc-links.mjs` 与 `git diff --check` 通过。

## Not in this ticket

- Phase 3A 的摘要、改写、意图树或 Prompt 实现。
- `qwen-plus` 是否开放三个服务等级；由 3A Step C 统一冻结。
- 深度思考、供应商任意参数透传、运行时热更新或诊断 Schema 变更。
- DashScope 原生 Rerank adapter；它属于并行收口的 Phase 2 工单 23。
