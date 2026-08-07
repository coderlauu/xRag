# 05 — 只读调度总览 API

**What to build:** 从 catalog、breaker registry 和 writer 计数组装 `GET /api/v1/ai/diagnostics/overview`；本工单不修改前端。

**Blocked by:** 01, 04, 09

**Status:** done（2026-08-07）

## Slice

- 返回 deployment 的 enabled/configured/verified capabilities 与当前 breaker 状态。
- 返回诊断 dropped/write-failure 计数及 registry 重建语义。
- 无 Provider、缺 secret、配置禁用时返回稳定空/状态对象而非 500。
- DTO 与序列化层禁止 secret、完整 Provider request ID、正文和原始错误。
- 只实现 overview，不实现 calls 列表/detail 或 React。

## Acceptance

- [x] API-01：无 Provider 时 `deployments`/`breakers` 为空数组、`eligibleCandidates`/`openBreakers` 为 0，不抛错。
- [x] CFG-10：对**序列化后的响应文本**断言不含密钥值、`secretProperty` 属性名，以及 `authorization`/`apikey`/`secret` 任意大小写。
- [x] 无 Provider、UNCONFIGURED、配置禁用（`enabled=false`）、breaker OPEN 与 HALF_OPEN 五种状态均可构造并已断言。
- [x] 输出无操作型 CRUD/Key/手动 breaker 字段；DTO 不嵌入 `ModelProvider`，`baseUrl` 也不返回。
- [x] writer 计数读取失败返回 `null` 而不是 0，`failovers24h`/`failedOrInterrupted24h`/`oldestAvailableAt` 同此约定。
- [x] `./mvnw -B verify` 通过（clean 构建后 356 tests，0 failures，2 skipped）。
  最初记的是 367——`target/` 里残留着 `5aa33f4` 已删除的两个 Phase 1 测试类的旧 surefire 报告，把总数虚高了 11。计数请以 clean 构建为准。

**Design:** [API §5](../../../tech/model-scheduling/api.md#5-调度总览)

## Decisions（2026-08-04 与用户确认，实现前已定）

- **`retention` 的两个配置项由本工单引入**（`maxAgeDays` / `maxLogicalCalls`），工单 19 复用同一份配置，不各自定义。`oldestAvailableAt` 取自 `ai_logical_call` 的最早 `started_at`。
- **`verifiedCapabilities` 先返回空数组**，并在响应契约与本工单里注明"待工单 23 真实 Provider 准入补齐验证记录后才有值"。**禁止用 `capabilities` 顶替**——规格明确要求它只来自明确配置和真实验证记录，不把 adapter 理论能力当已验证。
- **`limits.maxContextTokens` 本期不返回**：`ModelDeployment` 上没有这个字段，为了一个示例字段扩模型不值得；需要时另开工单。

## Notes

- 数据来源已全部就位，无需新建基础设施：`ModelCatalogSnapshot`、`ModelBreakerRegistry.snapshots()`（已含 state / allowsCall / halfOpenPermissionInUse / lastChangedAt / lastChangeReason 与四个计数）、`DiagnosticRecorder.droppedCount()` / `writeFailureCount()` 均已是 Spring bean。只需新写 `ai_logical_call` 的 24 小时聚合与最早时间查询。
- CFG-10 应断言**序列化后的响应文本**不含密钥值与 Authorization，而不是只检查 DTO 字段：`ModelProvider.secretProperty()` 是属性名而非密钥值，但 DTO 一旦整体嵌入 `ModelProvider` 就会把它带出去。

## 实现记录（2026-08-07）

三个主源码落在 `com.app.ai.web`：`AiDiagnosticsOverview`（DTO）、`DiagnosticsOverviewService`（组装）、
`DiagnosticsOverviewController`（`GET /api/v1/ai/diagnostics/overview`）。四条实现约束值得记下：

- **`eligibleCandidates` 只能读 `ModelBreakerRegistry.snapshots()`，不能用 `snapshot(target, capability)` 逐个探测。**
  后者内部是 `computeIfAbsent`，一探测就会凭空建出 breaker 条目，让 `breakers` 从空列表变成"每个
  deployment-capability 一条"。registry 里查不到的单元按 CLOSED 处理。
- **合格候选跟着 `allowsCall` 走，不看状态名。** HALF_OPEN 且两个许可都被占用时同样不算候选，
  但它不计入 `openBreakers`——把"正在试探"混进开路计数会让页面虚报故障数。
- **时间字段用 `@JsonSerialize(using = ToStringSerializer.class)` 输出 ISO-8601 文本。** CFG-10 用裸
  `new ObjectMapper()` 序列化，没有 JSR-310 module，`generatedAt` 非空时会直接抛
  "Java 8 date/time type not supported by default"。附带好处是无论调用方用哪个 mapper 格式都一致。
- **retention 两个键定义在 `application.properties`**（`app.ai.diagnostics.retention.max-age-days`
  / `max-logical-calls`，默认 7 / 10000，均可由环境变量覆盖），服务用 `@Value` 注入。工单 19 读同两个键，
  不要再写死数字，否则页面声称的保留期会和实际清理行为分叉。

测试分两层：`DiagnosticsOverviewServiceTests`（7 例，纯内存，`jdbc` 传 null 覆盖"读不到"分支）与
`DiagnosticsOverviewIntegrationTests`（2 例，临时 schema + Flyway 真库）。后者刻意让窗口内那条调用
切换 **2 次**——只有这样 `count(*) filter (where failover_count > 0)` 才和 `sum(failover_count)` 区分得开；
写入一律走真实的 `JdbcDiagnosticStore`，`failover_count` 因此是由 FAILOVER attempt 推导的真实值。

**遗留：** `limits.maxContextTokens` 按 Decisions 未返回；`verifiedCapabilities` 恒为空数组，
等工单 23 的真实 Provider 准入补齐验证记录后才有值——届时需要回来改 `deployments()` 里那个 `List.of()`。
