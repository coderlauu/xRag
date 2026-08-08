# 01 — 3A 领域词汇、配置与 Prompt catalog

**What to build:** 建立 `com.app.qa` 模块边界、强类型业务 ID/闭集、已确认的统一配置对象和 classpath Prompt catalog；不创建数据库表或业务编排。

**Blocked by:** —
**Status:** complete

## Slice

- 定义 conversation/turn/message/subquestion/action 等 value type 与状态闭集。
- 绑定 3/3、8/12、65536 bytes、processing stale timeout 和 0.35/0.4/0.6/0.8/0.15。
- 启动校验交叉不变量及 BALANCED Chat sampling profile。
- 落 8 个 `prompts/qa/3a/*.md` 与无脚本的确定性模板渲染器。
- 定义最多 3 线程/队列 3 的专用 scoring executor Bean。

## Acceptance

- [x] 3A-CFG-01~12 自动化通过。
- [x] `com.app.qa` 不引用 Provider/route/runtime 包；业务类型无 temperature/topP。
- [x] Prompt 缺失、占位符错误和配置矛盾启动即失败，错误含完整配置路径但无正文。
- [x] 本工单不新增 controller、Repository 或模型调用；V7 属于独立工单 02。

**Evidence:** `QaUnderstandingConfigurationTests`、`PromptTemplateCatalogTests`、
`IntentCatalogEncoderTests`、`QaModelCatalogRequirementsTests`、`QaDomainVocabularyTests`；
`2026-08-07 cd backend && ./mvnw -q verify` 通过。

**Design:** [architecture §3, §10, §13](../../../../tech/qa-pipeline/architecture.md)、[test CFG](../../../../tech/qa-pipeline/test-matrix.md#2-3a-cfg--配置prompt-与启动校验)
