# 技术方案：大模型调度引擎（xrag Phase 2）

- `status`: approved（2026-08-02）
- `date`: 2026-07-28
- `prd`: [大模型调度引擎 PRD](../../docs/prd/2026-07-28-model-scheduling-prd.md)
- `ui`: [模型调度诊断页界面规格](ui-spec.md)
- `research`: [一手资料复核](../../docs/research/2026-07-28-model-scheduling-primary-source-review.md)
- `related`: [ADR 0003](../../docs/adr/0003-separate-model-capability-ports.md)、[ADR 0004](../../docs/adr/0004-reactive-streaming-runtime.md)、[ADR 0005](../../docs/adr/0005-separate-model-runtime-state.md)

## 1. 方案结论

Phase 2 建一个 `com.app.ai` 深模块。它对上层只暴露四个按能力分开的稳定端口：

```text
ChatClient
StreamingChatClient
EmbeddingClient（保留 Phase 1 端口）
RerankClient
        │
        ▼
┌─────────────────────────────────────────────────────┐
│ Model Scheduling                                    │
│ 配置快照 → 候选规划 → attempt 预算 → 错误分类        │
│          → 重试 / 熔断 / 故障转移 → 诊断快照         │
└─────────────────────────────────────────────────────┘
        │
        ▼
按能力分开的 Provider SPI
```

统一的是候选规划、预算、健康状态和诊断，不是请求/响应 DTO。Chat、流式 Chat、Embedding、Rerank 各自保留协议不变量。

第一版明确采用：

- 文件配置 + 环境变量密钥，重启生效；不做模型 CRUD 或热更新。
- 硬约束过滤后按“默认候选 → 显式优先级 → 稳定 deployment ID”确定排序；不做随机、权重或学习型路由。
- 同步有限响应使用 Spring `RestClient`；流式 Chat 使用 `WebClient` + Reactor。
- Resilience4j 熔断器，按 `deployment + capability` 隔离，状态在当前 JVM 内。
- 完成态调用诊断异步写 PostgreSQL；诊断失败不改变模型调用结果。
- 保留 Phase 1 `EmbeddingClient` 这个能力端口名称，但把方法集原子替换为显式身份的 command/result 契约。

## 2. 边界与成功标准

### 2.1 模块负责

- 读取和校验 Provider、模型部署、能力、等级、优先级和限制配置。
- 为一次逻辑调用生成不可变候选快照。
- 在总截止时间和 attempt 上限内执行重试与故障转移。
- 管理进程内熔断状态。
- 把供应商错误、请求 ID、用量和流式事件归一化。
- 产出不含正文的诊断记录。
- 给 `/diagnostics` 提供只读查询 API。

### 2.2 模块不负责

- Prompt 组装、对话记忆、检索、工具编排和最终问答 HTTP 接口。
- 供应商模型的运行时开通、购买、密钥创建或管理。
- 把不同 Embedding 模型变成“等价”。
- 多实例之间共享熔断窗口。
- 为诊断记录提供强一致、零丢失保证。

### 2.3 可验证成功

1. 上层代码不引用 Provider SDK、URL、密钥或供应商错误体。
2. 同一配置快照和同一约束产生同一候选顺序。
3. 同步调用、流式调用、Embedding 批次和 Rerank 都受同一总截止时间约束。
4. 流提交前可以换候选；提交后只能成功、取消或 `STREAM_FAILED`，不能混入第二个模型。
5. 现有知识库调用点迁移后仍在事务外发起 Embedding HTTP 请求。
6. 诊断数据库不可写时，成功的模型调用仍成功。

## 3. 模块与包边界

```text
com.app.ai
├── chat          # ChatClient、StreamingChatClient、请求/结果/流事件
├── rerank        # RerankClient、Rerank 请求/结果、Noop 降级
├── model         # Capability、ServiceTier、ModelIdentity、ModelDeployment
├── config        # 配置绑定、结构校验、脱敏快照和 fingerprint
├── provider      # 按能力分开的 Provider SPI 与协议适配器
├── scheduling    # 候选规划、预算、错误分类、重试、故障转移
├── resilience    # 熔断注册表、许可与状态事件
├── diagnostic    # 完成态诊断聚合、异步写入、查询和保留任务
└── web           # 只读诊断 API

com.app.knowledge.embedding
└── EmbeddingClient / RoutedEmbeddingClient
```

`com.app.ai` 是一个模块边界，不把 `scheduling`、`resilience`、`diagnostic` 暴露给业务调用方。业务测试以四个能力端口为主要测试面；Provider 是真实外部依赖，因此由生产适配器和可编程假适配器形成真实 seam。

### 依赖纪律

- `knowledge → ai`：只允许 `RoutedEmbeddingClient` 调用调度模块；知识库 Service 不依赖 `ai.provider` 或 `ai.scheduling`。
- `ai.web → ai.diagnostic/config/resilience`：只读，不触发模型调用。
- `ai.scheduling → provider/resilience/diagnostic`：编排依赖；反向依赖禁止。
- `provider` 不写数据库；供应商 HTTP 调用永远不处在业务事务中。
- 诊断写入使用自己的短事务，不加入知识库或未来问答事务。

## 4. 领域模型与配置

### 4.1 最小领域对象

| 对象 | 含义 | 关键约束 |
|---|---|---|
| `Capability` | `CHAT / STREAM_CHAT / EMBEDDING / RERANK` | 不用字符串猜能力 |
| `ServiceTier` | `ECONOMY / BALANCED / QUALITY` | Embedding 不用等级自由选模型 |
| `ModelIdentity` | 精确模型制品与关键输出约定的稳定内部 ID | Embedding 必须精确匹配；目录同时固定 artifact/version、预处理 fingerprint 与维度 |
| `ModelDeployment` | 某 Provider 上可实际调用的一个模型部署 | 候选和熔断的最小对象 |
| `QuotaDomain` | 共享同一限流/配额池的部署集合 | 429 只能切到独立配额域 |
| `LogicalCall` | 调用方看到的一次完整调用 | 包含一个或多个 work unit |
| `WorkUnit` | 一次需要独立完成的上游工作 | Chat/Rerank/流各 1 个；Embedding 每批 1 个 |
| `Attempt` | 对一个 deployment 的一次真实 HTTP 请求 | 重试和故障转移都会新增 |
| `RoutePlan` | 调用开始时生成的不可变候选快照 | 调用中不重新读配置 |

一个 Provider 可以有多个 deployment；同一 `ModelIdentity` 也可以有多个等价 deployment。对 Chat/Rerank，同一等级内不同模型可以进入候选链；对 Embedding，只有同一 `ModelIdentity` 的 deployment 可以互相切换。

### 4.2 配置文件

新增版本控制内的 `backend/src/main/resources/ai-models.yml`，由 Spring Config 导入。配置只保存非敏感信息：

```yaml
app:
  ai:
    policy-version: phase2-v1
    providers:
      aliyun-model-studio:
        adapter: openai-compatible
        display-name: 阿里云百炼
        base-url: https://example.cn-beijing.maas.aliyuncs.com/compatible-mode/v1
        secret-property: DASHSCOPE_API_KEY
        data-region: CN_BEIJING
        quota-domain: aliyun-cn-account
    deployments:
      aliyun-qwen-plus-cn:
        provider-id: aliyun-model-studio
        upstream-model: qwen-plus-2025-12-01
        model-identity: qwen-plus-2025-12-01
        capabilities: [CHAT, STREAM_CHAT]
        features: [TOOL_CALLING]
        tiers: [BALANCED]
        default-candidate: true
        priority: 10
        enabled: true
      aliyun-text-embedding-v3-cn:
        provider-id: aliyun-model-studio
        upstream-model: text-embedding-v3
        model-identity: text-embedding-v3
        capabilities: [EMBEDDING]
        priority: 10
        enabled: true
        embedding:
          legacy-single-deployment: true
          dimensions: 1024
          max-batch-items: 10
          max-input-tokens-per-item: 8192
          preprocessing-fingerprint: RAW_UTF8_V1
```

示例 Embedding deployment 没有可证明的固定 artifact version，因此显式标为 `legacy-single-deployment`，不能加入第二个等价 deployment。新 identity 或需要故障转移的 identity 必须另填已验证的 `artifact-version`，不能用浮动模型别名冒充版本证据。

示例 URL 中的 workspace 必须由部署环境替换。`secret-property` 保存的是 Spring Environment 属性名，不是密钥值；诊断 DTO 从类型上就不包含解析后的秘密。

### 4.3 启动校验

结构错误直接阻止启动：

- Provider/deployment ID 不唯一或不符合稳定 ID 格式；
- deployment 引用不存在的 Provider；
- 能力缺少对应限制，例如 Embedding 无维度或批次上限；
- 一个 `capability + tier` 有多个默认候选；
- 非 legacy Embedding identity 缺少 artifact version 或 preprocessing fingerprint；
- 同一 Embedding `ModelIdentity` 的等价 deployment 在维度、预处理或制品版本上不一致；
- 没有精确制品证据的 legacy Embedding identity 配置了多个 deployment；
- 配置允许 Rerank Noop，却把 Noop 登记成真实 deployment；
- 超时、窗口、attempt 或缓冲上限为零/负数。

秘密缺失不阻止应用启动。对应 Provider 显示为 `UNCONFIGURED`，所有 deployment 在路由阶段以“配置不完整”过滤；这延续 Phase 1 “应用可启动、能力在真正调用时明确失败”的行为。

配置加载后生成一个不可变 `ModelCatalogSnapshot` 和不含秘密的 SHA-256 `configFingerprint`。fingerprint 包含“秘密是否存在”这个布尔状态但不包含秘密内容：补上缺失 Key 会改变快照，轮换同一 Provider 的 Key 不会把历史配置误报成路由规则变化。一次调用只持有这个快照。

## 5. 业务端口与 Provider SPI

### 5.1 业务端口

概念签名如下，最终 Java record 字段以 [API 契约](api.md) 为准：

```java
interface ChatClient {
    ChatResult complete(ChatCommand command);
}

interface StreamingChatClient {
    Flux<ChatStreamEvent> stream(ChatCommand command);
}

interface RerankClient {
    RerankResult rerank(RerankCommand command);
}
```

保留 Phase 1 的 `EmbeddingClient` 端口名称，但不保留会丢失模型身份或执行事实的旧方法。接口替换与全部实现、测试假实现和生产调用点在工单 15 的同一个原子变更中迁移：

```java
record EmbeddingCommand(
        ModelIdentity modelIdentity,
        List<String> texts,
        CallOptions options) {
}

record EmbeddingResult(
        List<float[]> vectors,
        ModelIdentity modelIdentity,
        int dimensions,
        EmbeddingExecutionInfo execution) {
}

interface EmbeddingClient {
    EmbeddingResult embed(EmbeddingCommand command);
}
```

接口不提供 `embed(List)`、`dimensions()`、裸 String 重载或 default fallback，避免默认模型掩盖身份错误，也保证结果始终携带执行事实。工单 15 必须一次更新生产实现、测试 fake/匿名实现、Spring Bean 和知识库调用点，仓库不保留“接口已变但实现未迁移”的中间提交。知识库的入库、编辑、新增分块和未来查询向量必须从 `knowledge_base.embedding_model` 构造 command；预期维度来自知识库已固化的 `embedding_dimensions` 并由 Provider 结果校验。Spring wiring 测试必须证明这些生产调用点注入的是 routed 实现。

普通业务命令不提供 `providerId` 或 `modelId`。真实 Provider 测试和 Phase 4 评测通过独立的包内调试入口附加 `PinnedRoute`，不把精确选模变成未来问答 API 的公共参数。

### 5.2 Provider SPI

不用 `invoke(Object)` 或万能 `ModelRequest`。按协议不变量定义四个内部端口：

```java
interface ChatProvider {
    ProviderChatResult complete(ModelDeployment deployment,
                                ProviderChatRequest request,
                                ProviderAttemptContext context);
}

interface StreamingChatProvider {
    Flux<ProviderChatEvent> stream(ModelDeployment deployment,
                                   ProviderChatRequest request,
                                   ProviderAttemptContext context);
}

interface EmbeddingProvider {
    ProviderEmbeddingResult embed(ModelDeployment deployment,
                                  List<String> texts,
                                  ProviderAttemptContext context);
}

interface RerankProvider {
    ProviderRerankResult rerank(ModelDeployment deployment,
                                ProviderRerankRequest request,
                                ProviderAttemptContext context);
}
```

生产适配器与 `ProgrammableFakeProvider` 是两个实际使用者，因此这个 seam 不是为测试凭空创造的。Provider DTO 只在 `provider` 包内；供应商 SDK 类型、HTTP 头和原始错误体不得越过该包。

第一版适配器：

- `OpenAiCompatibleChatProvider`：同步 Chat + SSE 流式 Chat。
- `OpenAiCompatibleEmbeddingProvider`：OpenAI embeddings 形状。
- `OpenAiCompatibleRerankProvider`：百炼 `/reranks` 扩展；不假设标准 OpenAI 支持 Rerank。
- `ProgrammableFakeProvider`：CI 中模拟成功、HTTP 错误、超时、断流、取消和畸形响应。

## 6. 路由、预算与错误策略

### 6.1 两阶段候选规划

硬过滤顺序固定：

1. deployment 是否启用、Provider 密钥是否配置；
2. 是否支持所需 `Capability`；
3. Chat 是否支持由命令中的工具定义、assistant tool-call 历史或 tool-result 消息自动推导出的 `TOOL_CALLING`；
4. 服务等级是否匹配；Embedding 改为精确 `ModelIdentity` 匹配；
5. 数据地域、Provider 白名单或调试精确约束是否匹配；
6. 规划时的熔断快照是否允许；
7. 剩余总预算是否足以开始一次 attempt。

每个被过滤 deployment 都保存一个稳定原因。合格候选按以下键排序：

```text
defaultCandidate DESC
priority ASC
deploymentId ASC
```

第一版不使用随机、权重、实时延迟打分或“随机打散同优先级”。相同输入与配置快照必须产生同一链。

`TOOL_CALLING` 只冻结 Phase 3 已知消费者需要的传输能力标记。它是调度模块从 `ChatCommand` 结构推导的内部 requirement，不是调用方可单独关闭的公共 toggle。Phase 2 不选择工具、不执行工具，也不校验工具业务语义；其他未被当前 PRD 要求的能力递延到出现真实消费者后再设计。

规划时的熔断状态只用于解释。真正开始 HTTP 请求前还要原子调用 `tryAcquirePermission()`；若半开许可被其他并发调用占用，本候选记录 `BREAKER_PERMISSION_REJECTED`，不计真实 attempt，继续下一个候选。

### 6.2 一个预算覆盖全部恢复动作

`AttemptBudget` 持有一个调用开始时创建的单调 `Deadline`。`Deadline` 由可替换的 monotonic ticker（生产使用 `System.nanoTime()`）和允许时长计算，只暴露 `remaining()` / `isExpired()`；诊断使用的 wall-clock `Instant` 与预算判断分离，系统时间跳变不能延长或提前耗尽调用。预算还包含：

- 每个 work unit 的最大 attempt 数；
- 同一 deployment 的最大连续 attempt 数；
- 已消耗 attempt、等待和 Provider 请求时间。

默认值：

| 能力 | 总截止时间 | 每 work unit 最大 attempt | 同 deployment 最大 attempt |
|---|---:|---:|---:|
| Chat | 45s | 3 | 2 |
| 流式 Chat | 120s | 3 | 1（首有效块超时不原地重试） |
| Embedding | 15m | 3 | 2 |
| Rerank | 20s | 3 | 2 |

Chat、流式 Chat、Rerank 各只有一个 work unit。Embedding 的 `RoutePlan` 先固定全部合格候选，再取这些候选 `maxBatchItems` 的最小值切成 N 个 work unit；因此任何一个 work unit 都可以原样交给任一等价候选。每批独立恢复，但全部批次共享 15 分钟总截止时间。这样 70 个正常批次不会被“最多 3 次调用”误杀，同时任一批次也不会无限重试。

调用方可以缩短默认总截止时间，不能超过模块配置上限。等待 `Retry-After`、退避和故障转移都消耗同一 `Deadline`。开始每次 attempt 前重新取得 `remaining`；连接池取连接、建连、读取/响应和能力专属阶段超时都使用 `min(remaining, 对应 attempt cap)`。安排计时器时同时记录限制来源：若 `remaining` 是较小值且计时器触发，归类 `TOTAL_DEADLINE_EXCEEDED`；若阶段 cap 更小，归类对应阶段 timeout，不能事后靠时间戳猜测。若剩余时间不足最小请求预算则不发 HTTP，直接结束恢复。

### 6.3 错误分类与动作

Provider 适配器只产生包内 `AttemptFailure`，不自行决定重试，也不把供应商异常直接变成业务异常。`AttemptFailureType` 到公开 `ModelErrorType + FailurePhase` 是唯一映射；`FailurePhase` 只描述失败实际发生的阶段，不从缺失时间戳反推。

调用参数非法在发起 attempt 前返回 `INVALID_REQUEST`；启动配置非法直接阻止启动；运行时缺少密钥等配置状态参与候选过滤，全部被过滤时返回 `NO_ELIGIBLE_MODEL`。这三类都不是 Provider 响应协议失败，不计 breaker，也不能换候选掩盖。Provider 返回的畸形 JSON、错误 index、维度/条数不符、空流或违反能力契约则统一是 `PROVIDER_RESPONSE_INVALID`。

| 内部 `AttemptFailureType` | 公开错误 + `FailurePhase` | 同候选重试 | 换候选 | Breaker 结算 |
|---|---|---|---|---|
| `CONNECT_FAILED` | `NETWORK + CONNECT` | 最多 1 次 | 是 | 失败 |
| `CONNECT_TIMEOUT` | `TIMEOUT + CONNECT` | 否 | 是 | 失败或慢调用 |
| `RESPONSE_HEADERS_FAILED` | `NETWORK + RESPONSE_HEADERS` | 最多 1 次 | 是 | 失败 |
| `RESPONSE_HEADERS_TIMEOUT` | `TIMEOUT + RESPONSE_HEADERS` | 否 | 是 | 失败或慢调用 |
| `RESPONSE_BODY_FAILED` | `NETWORK + UPSTREAM_RESPONSE` | 最多 1 次 | 是 | 失败 |
| `RESPONSE_BODY_TIMEOUT` | `TIMEOUT + UPSTREAM_RESPONSE` | 否 | 是 | 失败或慢调用 |
| `SERVER_ERROR_RESPONSE` | `SERVER_ERROR + RESPONSE_HEADERS` | 最多 1 次 | 是 | 失败 |
| `RATE_LIMITED_RESPONSE` | `RATE_LIMITED + RESPONSE_HEADERS` | 仅合法且可接受的 `Retry-After` | 仅独立 `QuotaDomain` | rate-limited 单独计数，不计普通失败 |
| `UPSTREAM_REQUEST_REJECTED` | `INVALID_REQUEST + RESPONSE_HEADERS` | 否 | 否 | 不计 |
| `AUTHENTICATION_REJECTED` | `AUTHENTICATION + RESPONSE_HEADERS` | 否 | 否 | 不计，标配置问题 |
| `PERMISSION_REJECTED` | `PERMISSION + RESPONSE_HEADERS` | 否 | 否 | 不计，标配置问题 |
| `FIRST_UPSTREAM_EVENT_TIMEOUT` | `TIMEOUT + FIRST_UPSTREAM_EVENT` | 否 | 提交前是 | 失败或慢调用 |
| `FIRST_MEANINGFUL_CHUNK_TIMEOUT` | `TIMEOUT + FIRST_MEANINGFUL_CHUNK` | 否 | 提交前是 | 失败或慢调用 |
| `UPSTREAM_RESET_BEFORE_FIRST_EVENT` | `NETWORK + FIRST_UPSTREAM_EVENT` | 否 | 提交前是 | 失败 |
| `UPSTREAM_RESET_DURING_PROBE` | `NETWORK + FIRST_MEANINGFUL_CHUNK` | 否 | 提交前是 | 失败 |
| `UPSTREAM_ERROR_EVENT` | `SERVER_ERROR + UPSTREAM_STREAM` | 否 | 提交前是 | 失败 |
| `PROVIDER_RESPONSE_INVALID` | `PROTOCOL_CONTRACT + PROVIDER_RESPONSE_VALIDATION` | 否 | 提交前是 | 失败 |
| `STREAM_IDLE_TIMEOUT` | `TIMEOUT + STREAM_IDLE` | 否 | 提交后否 | 失败或慢调用 |
| `UPSTREAM_STREAM_INTERRUPTED` | `STREAM_INTERRUPTED + UPSTREAM_STREAM` | 否 | 提交后否 | 失败 |
| `TOTAL_DEADLINE_EXCEEDED` | `TIMEOUT + TOTAL_DEADLINE` | 否 | 否 | 活跃 attempt 记失败；attempt 外不结算 |
| `CALLER_CANCELED` | `CANCELED + null` | 否 | 否 | 释放许可，不计 |

`FailurePhase` 的稳定值为 `CONNECT / RESPONSE_HEADERS / UPSTREAM_RESPONSE / FIRST_UPSTREAM_EVENT / FIRST_MEANINGFUL_CHUNK / STREAM_IDLE / UPSTREAM_STREAM / PROVIDER_RESPONSE_VALIDATION / TOTAL_DEADLINE`。`UPSTREAM_RESPONSE` 表示有限响应已收到 headers、但 body 尚未完整读完时发生的 I/O 失败或超时；完整 body 的格式与字段校验失败仍是 `PROVIDER_RESPONSE_VALIDATION`。`UPSTREAM_STREAM` 表示合法 SSE 中由 Provider 明确声明的错误，或提交后的非 idle 上游中断；它不用于畸形事件。取消不是失败阶段，因此 phase 为 null。Phase 2 的 WebClient 适配器必须遵循下游 demand，不引入一个尚不存在的“桥接缓冲溢出”错误。

attempt 的 `outcome` 可以用 `EMPTY_STREAM` 等稳定值细化本次上游结果，但不能绕过上述映射表另行决定重试、切换或 breaker；调用级 `terminationReason` 只解释整个逻辑调用为何结束。未知供应商错误码也只能先归到表内类型。

提交状态是表中“换候选”的最终门：即使某类错误通常可切换，只要已发出 `STREAM_STARTED` 就不得切换。所有可恢复候选耗尽时，最终异常保留最后一个 attempt 映射出的 `ModelErrorType + FailurePhase + attemptedExecution`，另以 `TerminationReason.ALL_CANDIDATES_EXHAUSTED` 表示停止恢复的原因；不能用一个聚合 error type 覆盖根因。

停止原因只使用 API 冻结的稳定枚举：调用前校验失败为 `INVALID_REQUEST`，无候选为 `NO_ELIGIBLE_MODEL`，`UPSTREAM_REQUEST_REJECTED`/鉴权/权限等不可恢复响应为 `NON_RECOVERABLE_ERROR`，429 无法等待且没有独立配额候选时为 `ALL_CANDIDATES_EXHAUSTED`（error type 仍是 `RATE_LIMITED`），未提交时总 deadline 到期为 `DEADLINE_EXCEEDED`，取消为 `CALLER_CANCELED`。

流式调用的停止原因有明确优先级：调用方取消在提交前后都使用 `CALLER_CANCELED`；一旦已经下游提交，任何非取消失败——包括总 deadline、idle timeout、合法 error event、协议错误或 reset——统一使用 `STREAM_INTERRUPTED_AFTER_COMMIT`，而具体根因仍由 `ModelErrorType + FailurePhase` 保留。这样不会同时为一个终态选择 `DEADLINE_EXCEEDED` 和 `STREAM_INTERRUPTED_AFTER_COMMIT`。

429 的原候选等待条件是：`Retry-After` 合法、等待不超过 5 秒、且等待后仍至少剩一次请求的最小预算。`Retry-After` 缺失、非法、超过上限或不适合剩余 `Deadline` 时，不在同候选做 jitter 重试；只能切到 `quotaDomain` 不同的候选，没有时直接返回 `RATE_LIMITED`。仅 `NETWORK`/`SERVER_ERROR` 等允许原地重试的非 429 错误使用带 full jitter 的退避，基线 200ms、上限 2s。时间、jitter 和 sleeper 都可替换，测试不靠真实睡眠。

## 7. 熔断器

第一版使用 Resilience4j `CircuitBreaker` 的底层许可 API，不用注解包裹。原因是流式调用的方法返回不代表调用完成，必须在流终态手工结算。

### 7.1 故障域

熔断 key：

```text
providerId / deploymentId / capability
```

`deploymentId` 已唯一指向 Provider、上游模型和端点；把 capability 加入 key，避免 Chat 故障误伤 Embedding。`QuotaDomain` 只处理 429，不代替熔断 key。

### 7.2 默认参数

| 参数 | 默认值 |
|---|---:|
| 滑动窗口 | count-based 20 |
| 最小调用数 | 5 |
| 失败率阈值 | 50% |
| 慢调用率阈值 | 50% |
| OPEN 等待 | 30s |
| HALF_OPEN 许可 | 2 |
| HALF_OPEN 最大等待 | 30s |

慢调用阈值按能力配置：Chat 15s、Embedding 单批 20s、Rerank 10s；流式 Chat 用“首个有效业务块耗时”判慢，不用整条生成时长。

### 7.3 流式结算

1. 订阅并真正开始上游请求前申请许可。
2. 正常终态才 `onSuccess`；慢调用时传首有效块耗时。
3. 提交前失败或提交后中断 `onError`。
4. 调用方取消使用 `releasePermission`，不伪装成功也不记录失败。
5. 终态由原子状态守卫，保证许可只结算一次。

Breaker 事件监听器把状态变化投递给诊断写入器。`DISABLED` 与配置禁用分开显示；第一版不提供 UI 强制打开、关闭或重置。

### 7.4 单实例语义

状态存在当前 JVM 的 `CircuitBreakerRegistry`。应用重启后全部从 `CLOSED` 重新采样；多个实例会各自形成窗口，不代表全局健康。这与当前单实例部署一致。

若未来多实例，候选规划和 CAS 许可接口保持不变；升级点是 `BreakerRegistry` 的状态协调或把调度独立成服务，不在 Phase 2 预付分布式复杂度。

## 8. 同步执行骨架

Chat、Embedding 单批和 Rerank 共享包内执行模板：

```text
创建逻辑调用诊断
  → 取得配置快照并规划候选
  → 检查取消与总截止时间
  → 对候选申请 breaker 许可
  → 发起 Provider attempt
  → 校验能力专属结果
  → 结算 breaker
  → 成功返回 / 分类错误后重试或故障转移
  → 生成完成态诊断快照
```

共享模板只掌握 `AttemptOperation<T>` 和结果校验回调，不知道 Chat message、Embedding vector 或 Rerank document 的具体结构。这是模块内部 seam，不向业务端口暴露。

Step D 显式加入 Apache HttpClient 5，不依赖 Spring Boot 3.4.1 对 `RestClient` 默认 request factory 的自动选择。当前 dependency management 对应 Spring Framework 6.2.1 与 HttpClient 5.4.1；同步 Provider 使用预配置 `HttpClient` 的 `HttpComponentsClientHttpRequestFactory`，并由自定义 `HttpContext` 在每个 attempt 注入 `RequestConfig`：connection-request、connect 与 response（socket read）timeout 都取 `min(Deadline.remaining(), 对应 attempt cap)`；共享连接管理器只提供更宽的静态安全上限，不能覆盖当次更短的 request timeout。只在外层做 `Future.get(timeout)` 而让底层请求继续运行不符合契约。实现依据见 [Spring 6.2 `HttpComponentsClientHttpRequestFactory` Javadoc](https://docs.spring.io/spring-framework/docs/6.2.x/javadoc-api/org/springframework/http/client/HttpComponentsClientHttpRequestFactory.html) 与 [Apache HttpClient 5.4 `RequestConfig` Javadoc](https://hc.apache.org/httpcomponents-client-5.4.x/current/httpclient5/apidocs/org/apache/hc/client5/http/config/RequestConfig.html)。

socket/阶段 timeout 可能因持续读到少量数据而重新计时，不能单独证明总 deadline。每个同步 attempt 还必须基于同一个 monotonic `Deadline.remaining()` 安排一次绝对到期 abort 定时器；它不因连接、header 或 body 活动重置。到期、显式取消、线程中断与正常完成竞争同一个一次性终态守卫，胜者负责 abort/结算，其他路径只做幂等清理。

同步取消必须可主动中止 I/O。`CallOptions.CancellationToken` 支持注册一次性取消回调并返回可关闭的 registration；若注册前已经取消，回调在注册过程中立即执行一次，关闭 registration 幂等。当前 attempt 在发请求前把回调绑定到底层 Apache request abort，attempt 终态立即注销。取消与完成竞态仍由一次性终态守卫处理，不能只轮询 boolean。若阻塞线程被中断，先中止当前 request、停止恢复，再恢复线程的 interrupted 标志。

## 9. 流式执行

### 9.1 运行时选择

引入 `spring-boot-starter-webflux` 只为 `WebClient`、Reactor Netty 和 Reactive Streams；应用继续以 Spring MVC/Tomcat 运行，不切换成 WebFlux Server。有限响应仍走 `RestClient`。

业务端口返回 `Flux<ChatStreamEvent>`。每次调用 `stream(command)` 创建一个**单订阅** publisher：第一次订阅才创建逻辑调用并开始路由；第二次订阅（包括调用方对同一个 Flux 使用 `retry` 导致的重订阅）立即以 `IllegalStateException("stream publisher allows only one subscription")` 拒绝，且不创建新的逻辑调用、不发 HTTP。它属于 publisher 生命周期误用，不伪装成一次模型调用失败。调用方确需新调用时必须再次调用 `stream(command)`。

流取消以 Reactive Streams subscription cancel 为最终信号，不使用同步端口的 `CancellationToken` 轮询。取消必须沿组合流传播到当前 `WebClient` 请求并阻止后续候选。

### 9.2 SSE 解析

- 使用 Spring 的 `ServerSentEvent<String>` 解码器处理 UTF-8、CR/LF/CRLF、多 `data:` 行、注释和空行分隔。
- 不把 TCP chunk 或 `readLine()` 当成完整事件。
- Provider 适配器再把 SSE data JSON 归一成内部 `ProviderChatEvent`。
- Provider 协议中合法且明确的 error event 不得当未知事件跳过；Phase 2 统一产生 `UPSTREAM_ERROR_EVENT → SERVER_ERROR + UPSTREAM_STREAM`，供应商原始 code/message 不能越过 seam。
- 未知但合法的供应商事件跳过并记录低基数类型；畸形 JSON 或违反契约的事件产生内部 `PROVIDER_RESPONSE_INVALID`，对外映射 `PROTOCOL_CONTRACT + PROVIDER_RESPONSE_VALIDATION`。
- 单个 SSE 事件最大 64KiB，超过即以同一 Provider 响应协议失败终止当前 attempt。

### 9.3 探测、提交与缓冲

内部状态：

```text
PROBING → COMMITTED → COMPLETED
       ↘ FAILED
       ↘ CANCELED
COMMITTED → STREAM_FAILED
          → CANCELED
```

记录三个不同时间：

- 首个合法上游事件；
- 首个有效业务块；
- 第一次下游提交。

有效业务块是非空文本增量、非空工具调用增量或明确拒答增量。心跳、usage、空 delta、角色元数据和供应商生命周期事件都不算。

探测期最多缓冲 32 个事件或 64KiB，任一上限先到即把当前 attempt 视为协议失败。收到首个有效业务块后：

1. 原子把状态改为 `COMMITTED`；
2. 先发 `STREAM_STARTED`（含实际 deployment 与逻辑调用 ID）；
3. 刷出已缓冲的业务事件；
4. 后续事件直通。

`STREAM_STARTED` 一旦下发，禁止切换候选。若之后上游断开，发送唯一终态 `StreamFailed`：只携带稳定 `TerminalCause`、稳定展示 message 与固定为 true 的 incomplete 标志，不含原始错误体、供应商异常或秘密。

若所有候选都在提交前失败或被过滤，下游收到零个 `ChatStreamEvent`，publisher 直接 `onError(ModelCallException)`；不得发送 `StreamFailed(incomplete=false)`。`StreamFailed` 只表示已经提交后的不完整结果，发出后 publisher 正常结束，不能再追加 Reactor error。

### 9.4 超时与背压

默认流式超时：

| 阶段 | 默认 |
|---|---:|
| 建连 | 3s |
| 响应头 | 10s |
| 首个合法事件 | 15s |
| 首个有效业务块 | 20s |
| 相邻有效事件空闲 | 30s |
| 逻辑调用总截止 | 120s |

不使用无界 `onBackpressureBuffer`。探测缓冲有显式上限；Phase 2 的所有流式 Provider 都由 `WebClient` 实现并必须在提交后遵循下游 demand，不提供不遵循 demand 的桥接适配器。

所有成功、失败和取消路径汇合到一个 `doFinally` 清理点，释放 HTTP 连接、熔断许可、计时器和诊断聚合器。

## 10. Embedding 迁移

Phase 1 的知识库表已经把 `embedding_model` 与 `embedding_dimensions` 固化到知识库。Phase 2 不改列名：从迁移完成起，`embedding_model` 的语义明确为 `ModelIdentity`，不是“本次实际 Provider 的模型字符串”。

当前本地数据库已有 `doubao-embedding-vision|1024` 与 `text-embedding-v3|1024`。这些字符串只是已有内部 key，本身不足以证明精确制品或等价向量空间。catalog 必须为每个可故障转移的 `ModelIdentity` 固定 artifact/version、preprocessing fingerprint 与 dimensions，并保存验证证据。两者必须作为不同身份处理；维度相同不能互相故障转移。

无法补齐上述证据的现有 identity 只能标记为 `legacy-single-deployment`：恰好绑定一个 deployment，禁止跨 deployment 故障转移。把已有知识库改绑到另一个 identity 或改变其 artifact/preprocessing/dimensions，都必须走独立的全量向量重建，不能只改 catalog 映射。

迁移由工单 15 原子完成：

1. 加入 `EmbeddingCommand / EmbeddingResult / embed(EmbeddingCommand)`，删除旧方法，并同步更新所有生产实现、测试假实现、匿名实现、Spring Bean 和知识库调用点。
2. 把现有 OpenAI-compatible 编解码、批次顺序/维度校验迁入 `EmbeddingProvider`，由 `RoutedEmbeddingClient` 实现唯一业务入口。
3. 在同一提交内跑完整编译与 Phase 1 知识库测试；若未恢复全绿，该原子变更不得合并。

每个 Embedding work unit 校验：

- 返回条数与本批输入相同；
- index 不重复、不越界，并按 index 还原；
- 每条向量维度与模型身份一致；
- 所有值是有限数，不含 `NaN` 或无穷；
- 多批结果按原输入顺序拼接。

每批可以在等价候选间独立恢复，已经成功的批次不重算，因此一次**成功返回结果**的逻辑调用可能由多个 deployment 共同贡献。进程内 `EmbeddingExecutionInfo` 按首次成功贡献的批次顺序暴露去重的 `contributingDeployments` 和逐批 `EmbeddingBatchExecution`，不再另设容易被误读的 singular actual；`mixedExecution` 当且仅当 contributor 数量大于 1。

持久化 `ai_logical_call` 是供跨能力列表使用的摘要：contributor 数量为 1 时填写调用级 `actualProviderId / actualDeploymentId / actualModelIdentity`；数量大于 1 时三者均为 null，并保存 `mixedExecution=true` 与实际 contributor count。不能任选最后一个或占比最高者冒充整次调用事实，逐批来源以 attempt/能力详情为准。

若任一批最终失败，或在完整结果返回前调用被取消，逻辑调用都不向下游贡献任何 vectors：异常只返回通用失败执行信息，持久化 call 的 contributor count=0、mixed=false、actual 三字段均为 null。此前成功批次仍作为 `SUCCEEDED` attempts 保留在诊断详情，不能因此把整个失败/取消调用标成已贡献结果。

若某知识库身份在配置中没有合格 deployment，明确失败，不自动用默认模型。现有向量不在 Phase 2 自动重建；更换身份必须作为未来独立“全量重建”操作。

## 11. Rerank 与 Noop

`RerankCommand` 使用稳定候选 ID + 可发送文本，不接收裸字符串列表。Provider 返回 index 后先校验，再映射回稳定 ID。

Noop 是 `RerankClient` 的显式最终降级，不是 Provider：

- 仅在目录没有声明可用 Rerank 能力、调用约束没有合格候选，或所有可恢复候选耗尽时使用；
- 保持输入顺序并截取 top-N；
- 返回 `rerankApplied=false` 和稳定降级原因：目录未声明能力为 `CAPABILITY_NOT_DECLARED`，调用约束下无合格候选为 `NO_ELIGIBLE_CANDIDATE`，可恢复候选耗尽为 `RECOVERABLE_CANDIDATES_EXHAUSTED`；
- 不创建 Provider attempt，不改变任何 breaker；
- 永不伪造 relevance score。

非法请求、调用方取消和鉴权/配置错误不降级为 Noop；这些情况直接失败，避免把配置事故伪装成正常质量下降。特别地，“目录没有声明 Rerank 能力”是允许降级的产品状态，而“已有本应匹配的 Rerank deployment 但 secret 缺失或运行时返回 401/403”是配置故障，两者不能都折叠成 `NO_ELIGIBLE_MODEL → Noop`。

## 12. 诊断写入

每个逻辑调用在内存中聚合候选、真实 HTTP attempt、非 HTTP route event 和最终结果。route event 至少包含 `ROUTE_COMPLETED / BREAKER_PERMISSION_REJECTED / RETRY_SCHEDULED / RETRY_WAIT_STARTED / FAILOVER_SELECTED / BUDGET_EXHAUSTED / CALL_TERMINATED`，按逻辑调用内的单调 sequence 与实际发生时间记录。Breaker 许可竞争、等待和切换事实不得由 candidate 快照或 attempt 间隙推导。

终态产生一个不可变 `CompletedCallDiagnostic`，通过非阻塞 `offer` 投递到容量 1,000 的队列；单线程写入器最多每 250ms 或每 50 条批量写 PostgreSQL。

选择“终态一次写入”而不是每个事件同步落库：

- 不把数据库延迟加入首包路径；
- 一次短事务写 call/candidate/attempt/route-event，避免半条链路；
- 写入失败只丢诊断，不回滚模型结果。

代价：

- 进程崩溃时尚未结束的调用和队列尾部可能缺失；
- 页面第一版不展示正在进行的调用；
- 队列满或数据库失败时必须递增内存计数、打节流 WARN，并在诊断页显示“近期诊断丢弃数”。

应用优雅停止时最多等待 2 秒 flush，不无限阻塞停机。

Breaker transition 独立投递；当前 breaker 状态始终从进程内注册表读取，数据库只提供历史。

## 13. 真实 Provider 准入

### 13.1 推荐基线

Phase 2 推荐用一把**通用按量计费**的阿里云百炼 Model Studio Key 完成真实验证，原因是同一中国区 workspace 能覆盖：

- Chat / 流式 Chat：固定日期版本的 Qwen 模型；
- Embedding：`text-embedding-v3`（兼容已有一个知识库身份）或新建库使用 `text-embedding-v4`；
- Rerank：`qwen3-rerank` 的 `/reranks` 接口。

模型配置必须 pin 固定版本；`qwen-plus` 这类浮动别名只用于人工探索，不进入已批准的生产候选。

截至 2026-07-28 的官方约束快照：

- `text-embedding-v4`：每请求最多 10 条、单条最多 8,192 token，支持 1024 维；北京区价格为每 1,000 输入 token 0.0005 CNY。
- `qwen3-rerank`：最多 500 个文档、每文档最多 4,000 token、单请求最多 120,000 token；超长内容由 xrag 在调用前拒绝或显式截断，不能依赖供应商静默处理。
- 百炼限流按根账号聚合 workspace、RAM 用户和 API Key；不同 Key 不必然代表独立 `QuotaDomain`。

来源：[Embedding 官方说明](https://help.aliyun.com/en/model-studio/embedding)、[Rerank 官方说明](https://help.aliyun.com/en/model-studio/rerank)、[限流官方说明](https://help.aliyun.com/en/model-studio/rate-limit)、[价格页](https://help.aliyun.com/en/model-studio/model-pricing)。

价格会变化，配置不硬编码单价。真实测试工单开始前重新核对价格，限制为每能力不超过 20 次短请求，并在云控制台启用费用告警。

### 13.2 凭据状态与禁止事项

当前工作区没有 `DASHSCOPE_API_KEY`；真实 Provider 工单开始前由项目所有者创建中国区通用 `sk-...` Key，并只写入忽略的本地 `.env` 或部署秘密。

现有 `EMBEDDING_API_KEY` 是 Phase 1 遗留凭据，已知用途受限。迁移期间允许它仅维持原 Embedding 回归，不把它扩展到 Chat、流式 Chat 或 Rerank，也不把它标为已合规 Provider。

Coding Plan 的 `sk-sp-...` 只允许 AI 编程工具，不能用于自建后端、curl、Postman 或批处理；违规可能停订阅或禁 Key。来源：[阿里云 Coding Plan FAQ](https://help.aliyun.com/en/model-studio/coding-plan-faq)。`ANTHROPIC_AUTH_TOKEN` 等当前 Codex/Claude 工具环境变量同样不得被应用读取或写入配置。

### 13.3 准入清单

一个真实 deployment 只有在以下各项都有证据后才能 `enabled=true`：

1. Key 用途与 API 调用条款合规；
2. 区域、endpoint 和数据边界明确；
3. 能力实际调用通过，不能由“OpenAI compatible”推断；
4. 模型版本、上下文、批次、维度、Rerank 文档上限已登记；
5. 429 响应、请求 ID 和错误体已经过脱敏检查；
6. 价格与免费额度已复核，费用告警已设置；
7. 同一 `QuotaDomain` 的范围由账号级规则确认；
8. 真实测试凭据只在本地/部署秘密中，CI 继续使用假 Provider。

## 14. 关键取舍

| 取舍 | 采用 | 放弃及原因 |
|---|---|---|
| 能力接口 | 四个稳定端口，共享内部骨架 | 万能 `invoke(Object)` 会丢失能力不变量 |
| 路由 | 硬过滤 + 确定排序 | 随机/权重降低可复现性；学习型路由缺 Phase 4 数据 |
| 同步 HTTP | `RestClient` | 全量 Reactive 重写无收益 |
| 流式 HTTP | `WebClient` + Reactor | 阻塞读 + 线程池很难正确处理取消、背压和资源释放 |
| 熔断 | Resilience4j + 手工许可结算 | 自研统计窗口和半开竞争风险高 |
| 熔断状态 | JVM 内 | 当前单实例不需要分布式协调 |
| 诊断 | 完成态异步写 PostgreSQL | 同步逐事件写会污染首包；仅日志无法支持详情页与跨重启 |
| 配置 | 文件 + env，重启生效 | 运行时 CRUD/热更新不在产品范围 |
| Embedding | 保留能力端口名称，原子替换为 command/result 方法集 | 旧方法会丢身份与执行事实；default fallback 会掩盖漏迁移实现 |

## 15. Step C 评审点

进入实现前需要项目所有者确认：

1. 接受四个能力端口 + 内部共享调度骨架。
2. 接受 MVC 应用内为流式客户端引入 WebClient/Reactor。
3. 接受进程内 breaker 在重启后清零、多实例不共享。
4. 接受完成态诊断的“主调用优先、允许少量诊断丢失”语义。
5. 接受阿里云百炼通用 Key 作为推荐真实验证凭据；Key 未就绪只阻塞真实测试，不阻塞假 Provider 实现。
6. 接受唯一错误动作表，以及“根因 error/phase”和“调度停止原因”分开表达。
7. 接受流式 single-subscription、提交前零事件 `onError`、提交后唯一 `StreamFailed` 的终态契约。
8. 接受 Embedding 等价证据、legacy single-deployment、mixed execution 与失败时不返回部分 vectors 的保护线。
9. 接受五张诊断表、真实 route event/failure phase，以及成功贡献者与最后尝试目标分开表达。
