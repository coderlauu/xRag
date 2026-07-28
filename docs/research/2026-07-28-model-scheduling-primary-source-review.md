# Phase 2 大模型调度引擎一手资料复核

- 日期：2026-07-28
- 范围：统一 Chat / 流式 Chat / Embedding / Rerank 客户端、模型路由、三态熔断、故障转移、SSE、异步执行和调度诊断
- 对应计划：[Phase 2 大模型调度引擎](../exec-plans/active/2026-07-28-model-scheduling-phase-plan.md)
- 研究性质：Step A 学习支撑；本文不冻结 Phase 2 的 API、Schema、依赖库或配置格式

## 结论摘要

1. **统一的是调度骨架，不是把四种能力压进一个万能请求/响应 DTO。** Chat、Embedding、Rerank 的协议不变量不同：Chat 有消息、完成原因和可选流式事件；Embedding 要保证输入与向量按 `index` 一一对应并校验维度；Rerank 要保留候选身份、返回排序位置和相关性分数。适合共享的是 Provider/模型/能力注册、路由、尝试记录、超时、错误分类、熔断与观测。
2. **路由应先做硬约束过滤，再做软策略排序。** 必须先剔除不支持所需能力、协议、流式、上下文规模或数据地域要求的候选，再按明确的优先级/成本/延迟策略排序。学习型路由能做质量与成本权衡，但需要偏好数据和评测闭环；xrag 在 Phase 4 评测能力出现前，先做确定性、可解释路由更合适。
3. **路由、重试、故障转移是三件事。** 路由产生有序候选；重试是在同一候选上再次尝试；故障转移是换到下一个合格候选。三者必须共享一个总截止时间和尝试预算，避免每层各自重试造成请求与延迟倍增。
4. **熔断器的核心不是三个枚举值，而是统计窗口、最小样本、失败分类和半开许可。** Resilience4j 的正式状态机还包括 `DISABLED`、`FORCED_OPEN` 等特殊状态；课程中的“三态”应理解为正常运行的 `CLOSED → OPEN → HALF_OPEN` 主干。鉴权失败、模型名错误、参数校验失败、调用方取消不应默认计入后端健康失败。
5. **熔断键应至少隔离到“Provider + 模型 + 能力/端点”。** 同一个供应商的 Chat 故障不应把仍可用的 Embedding 一起熔断；同一供应商不同模型也可能有独立配额或健康状态。Phase 2 若维持单实例部署，可先使用进程内状态；多实例下各实例的熔断窗口并不自动等价于全局状态，必须在技术方案中明确语义。
6. **SSE 必须按事件语法解析，不能把每一行或每次 TCP read 当作一个完整业务事件。** WHATWG 规范允许 CRLF/LF/CR 换行、一个事件包含多条 `data:`、空行结束事件、冒号行作为注释；事件流始终按 UTF-8 解码。上游 OpenAI 风格流还会有协议终止标记或不同事件类型，Provider 适配器必须把传输事件归一成内部事件。
7. **“首包”至少要拆成首个上游事件、首个有业务意义的增量、首个下游提交三个时刻。** SSE 注释、响应元数据、空 delta 都可能早于真正文本/工具参数。探测成功条件必须按能力和事件类型定义，不能只看“收到了字节”。建议诊断同时记录 `time_to_first_upstream_event`、`time_to_first_meaningful_chunk` 和 `time_to_first_downstream_commit`。
8. **流式故障转移的安全边界是下游提交点。** 在任何上游内容转发给调用方之前，可以缓冲探测并切换候选；一旦已经提交某个模型的内容，再把另一模型从头生成的内容拼进同一流会破坏语义。此后发生上游中断，应发送明确终止/错误事件并结束，而不是“无感”混流。该结论是根据 SSE 事件语义、OpenAI 的增量事件结构和 Envoy 对“响应开始前/后 reset”的区分作出的工程推论。
9. **异步流必须具备取消传播、背压和统一清理路径。** Reactive Streams 要求取消最终停止信号并释放订阅关系；Spring 明确把 `RestClient` 定位为同步客户端，把 `WebClient` 定位为非阻塞、异步和流式客户端。xrag 可以继续用同步 `RestClient` 承载 Embedding，但流式 Chat 不应仅靠“扔进线程池 + 阻塞读”假装异步，除非技术方案明确线程、缓冲上限、取消和资源释放。
10. **诊断记录默认只存调度事实，不存 Prompt/输出正文。** OpenTelemetry GenAI 约定把模型、Provider、流式标记、完成原因、token 和首块耗时作为观测属性，而输入输出内容是 opt-in 且明确警告可能包含敏感信息。xrag 的轻量诊断页应优先展示候选过滤原因、尝试顺序、熔断状态、Provider 请求 ID、首包/总耗时和错误类型。

## 1. 统一 AI 基础设施层的接口边界

### 1.1 一手资料显示的协议差异

| 能力 | 官方协议事实 | 必须保留的不变量 |
|---|---|---|
| 同步 Chat | OpenAI Chat Completions 接收消息并返回完成对象，响应包含实际模型、完成原因和 usage 等字段 | 请求模型与实际响应模型不能混为一谈；完成原因、usage、Provider 请求 ID应保留 |
| 流式 Chat | `stream=true` 时按 SSE 返回增量；启用 `include_usage` 时，完整 usage 位于 `[DONE]` 前额外 chunk，流中断时可能拿不到 | 流式 usage 是可缺失信息；增量事件、完成事件、错误/取消必须是显式状态 |
| Embedding | `POST /embeddings` 支持批量输入，响应每项带 `index`；可选 `dimensions` 只适用于部分模型 | 返回必须按 `index` 还原，并校验条数、重复/越界 index 和向量维度 |
| Rerank | Cohere `v2/rerank` 接收 `query + documents + model + top_n`，返回按相关性排序的 `index + relevance_score` | 排名结果应引用原候选身份；Provider 对候选数、文档长度和截断有自己的限制 |

来源：

- [OpenAI：Create chat completion](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
- [OpenAI：Create embeddings](https://developers.openai.com/api/reference/resources/embeddings/methods/create)
- [Cohere：Rerank API v2](https://docs.cohere.com/v2/reference/rerank)

### 1.2 对 xrag 的接口建议

Step A 学习稿可先使用下面的概念分层，不在此时冻结 Java 签名：

```text
业务能力端口
  ChatClient
  StreamingChatClient
  EmbeddingClient
  RerankClient
        │
        ▼
共享调度骨架
  CapabilityFilter → RoutePolicy → AttemptExecutor
                         │
                         ├─ RetryPolicy
                         ├─ CircuitBreaker
                         ├─ FailoverPolicy
                         └─ DiagnosticRecorder
        │
        ▼
Provider 适配器
  OpenAI-compatible / provider-native
```

这里“统一入口”的合理含义是上层不接触供应商 SDK 和错误结构，而不是一个 `invoke(Object)`。不同能力端口可以复用同一个调用上下文，例如：

- 业务 trace / request ID；
- 所需能力与流式标记；
- 目标模型约束；
- 总截止时间和取消信号；
- 允许的 Provider / 数据地域；
- 诊断标签。

Provider 适配器负责：

- 把内部请求翻译成供应商协议；
- 把供应商错误归一成稳定错误分类；
- 把供应商流式事件归一成内部事件；
- 暴露供应商请求 ID 和限流元数据；
- 声明该适配器实际验证过的能力，不以“OpenAI 兼容”推断全部能力。

Envoy AI Gateway 的官方能力表同样强调：网关可以提供 OpenAI 兼容入口，但不同 Provider 只支持该协议的不同子集，且“预期可用”和“CI 已验证”必须区分。[Envoy AI Gateway：Supported API Endpoints](https://aigateway.envoyproxy.io/docs/0.3/capabilities/llm-integrations/supported-endpoints/)

## 2. 模型路由与智能选择

### 2.1 建议的两阶段路由

第一阶段是硬约束过滤：

1. 候选是否启用、配置完整；
2. 是否支持 `CHAT` / `STREAM_CHAT` / `EMBEDDING` / `RERANK`；
3. 是否支持请求需要的工具调用、结构化输出、维度或上下文长度；
4. 是否满足数据地域或明确的 Provider 白名单；
5. 熔断器是否允许调用；
6. 候选是否仍有本次请求的尝试预算。

第二阶段才做软策略排序：

- 显式优先级；
- 预估成本；
- 近期延迟或错误率；
- 模型质量等级；
- 负载与排队状态。

Envoy AI Gateway 的 Provider fallback 使用带优先级的后端列表，并在主后端失败或不健康时切到后续健康候选；其推理路由扩展也把实时负载、排队、KV cache 和端点健康作为动态选择信号。这说明“能力/健康过滤 + 有序候选”是可落地基线，而不是必须先训练一个路由模型：

- [Envoy AI Gateway：Provider Fallback](https://aigateway.envoyproxy.io/docs/capabilities/traffic/provider-fallback/)
- [Envoy AI Gateway：Inference Optimization](https://aigateway.envoyproxy.io/docs/capabilities/inference/)

### 2.2 学习型路由的适用边界

[RouteLLM 论文](https://arxiv.org/abs/2406.18665)研究的是根据偏好数据在强、弱模型之间动态选择，以优化质量和成本。它说明“智能路由”可以是学习问题，但落地需要：

- 稳定的候选模型池；
- 可代表真实业务的请求分布；
- 偏好/质量标签；
- 成本和质量的评测目标；
- 模型升级后的重新校准。

xrag 的 Phase 4 才建设评测体系，因此 Phase 2 第一版不应把“智能”解释成不可解释的 LLM 判路由。先实现确定性优先级、能力硬过滤、健康状态和完整诊断，后续有数据再增加学习型策略。

### 2.3 可解释路由的最低记录

每次逻辑调用至少应能回答：

- 原始所需能力是什么；
- 初始候选有哪些；
- 每个候选为何被过滤或排在后面；
- 最终尝试顺序是什么；
- 每次是原候选重试还是切换候选；
- 最终实际 Provider / 模型是什么；
- 是否因熔断、限流、超时或能力不匹配发生切换。

路由决策应是一次调用的快照。不要在调用中途重新读取一套已经变化的配置并生成完全不同的候选链，否则同一请求无法复盘。

## 3. 三态熔断器与故障转移

### 3.1 状态机的必要参数

Resilience4j 官方文档给出的关键事实：

- 正常主状态是 `CLOSED`、`OPEN`、`HALF_OPEN`；
- `CLOSED` 中按计数或时间滑动窗口统计失败率和慢调用率；
- 达到最小调用数后才计算阈值，避免一个失败立即熔断；
- `OPEN` 等待一段时间后进入 `HALF_OPEN`；
- `HALF_OPEN` 只放行有限数量探测调用，依据结果回到 `CLOSED` 或 `OPEN`；
- 异常可以配置为记录、忽略，或视为成功；
- 还有 `DISABLED`、`FORCED_OPEN` 等运维特殊状态。

来源：[Resilience4j：CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker)

因此学习稿不能只记录：

```text
失败 N 次 → OPEN → 等 T 秒 → HALF_OPEN
```

至少还要讨论：

- 计数窗口还是时间窗口；
- 最小样本数；
- 失败率与慢调用率阈值；
- 慢调用的时间定义；
- 半开许可数与并发竞争；
- 哪些错误影响健康度；
- 状态按什么键隔离；
- 状态变化如何诊断和测试。

### 3.2 建议的失败分类

| 失败 | 同候选重试 | 换候选 | 计入熔断 | 说明 |
|---|---|---|---|---|
| 连接失败、DNS、TLS、上游 reset | 可按预算 | 通常可 | 是 | 供应商/端点可用性问题 |
| 5xx / 503 | 可按策略 | 通常可 | 是 | 注意总截止时间 |
| 首个业务事件超时 | 不宜在同候选反复拖延 | 可 | 是或计慢调用 | 需按模型特性设置阈值 |
| 429 限流 | 尊重 `Retry-After`，否则带抖动退避 | 可切到配额独立候选 | 可单独计“受限”而非普通故障 | 429 不等于服务宕机 |
| 401 / 403 | 否 | 只有其他候选凭据独立时才可 | 通常否 | 配置/权限错误，重试无效 |
| 400、模型名错、维度错 | 否 | 通常否，除非错误明确为候选特有 | 否 | 请求或配置错误 |
| 调用方取消 | 否 | 否 | 否 | 不是 Provider 健康失败 |
| 流已提交后的中断 | 否 | 不在同一响应内切换 | 是 | 向下游发送终止错误 |

表中策略是面向 xrag 的设计建议，不是某个库的默认值；Step C 仍需按真实 Provider 错误结构确认。

### 3.3 重试预算和 `Retry-After`

RFC 6585 定义 429 为请求过多，并允许服务端通过 `Retry-After` 告知等待时间；RFC 9110 规定 `Retry-After` 可以是 HTTP 日期或延迟秒数：

- [RFC 6585：429 Too Many Requests](https://www.rfc-editor.org/rfc/rfc6585.html#section-4)
- [RFC 9110：Retry-After](https://www.rfc-editor.org/rfc/rfc9110.html#name-retry-after)

Envoy 的官方路由文档还强调：

- 总路由超时包含所有重试；
- 默认采用带抖动的指数退避；
- 可以使用 `Retry-After` / rate-limit reset 类响应头决定等待；
- 最终尝试数应可观测。

来源：[Envoy：Router / retries](https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/router_filter.html)

对 xrag 的直接含义：

- 当前 Embedding 只做固定指数倍增、没有抖动，也没有读取 `Retry-After`；
- Phase 2 可以把这一经验升级为共享策略，但不能让 Chat、Embedding、Rerank 无条件共用完全相同的重试规则；
- 一个逻辑调用要设置 `overallDeadline` 和 `maxAttempts`，重试与故障转移共同消耗它们；
- 诊断中要记录 `attempt=1..N`、等待来源和实际等待时间。

## 4. Chat 同步调用与公共执行模板

同步 Chat、Embedding 和 Rerank 可以共享如下模板步骤：

```text
校验调用上下文
  → 解析所需能力
  → 生成候选快照
  → 对候选逐个尝试
       → 熔断许可
       → Provider 调用
       → 归一化响应或错误
       → 记录尝试结果
       → 决定重试 / 故障转移 / 结束
  → 记录逻辑调用结果
```

不宜塞进同一个模板钩子的部分：

- Embedding 的批次切分、顺序还原和维度校验；
- Rerank 的候选截断、身份映射与 top-N；
- 流式 Chat 的订阅生命周期、探测缓冲和提交点；
- Provider 特有请求字段和错误体解析。

Spring Framework 6.2 将 `RestClient` 定位为同步客户端；它线程安全，适合当前 Embedding 这种短期、有限响应体调用。`WebClient` 则明确支持非阻塞、异步、Reactive Streams 背压和上下行流式数据。[Spring Framework 6.2：REST Clients](https://docs.spring.io/spring-framework/reference/6.2/integration/rest-clients.html)

所以“模板方法”应是执行语义的统一，不应强迫同步与流式共享同一个返回类型或同一套资源生命周期。

## 5. SSE 解析、异步执行与首包探测

### 5.1 SSE 解析基线

WHATWG HTML Living Standard 对事件流的要求包括：

- MIME type 是 `text/event-stream`；
- 永远按 UTF-8 解码；
- CRLF、CR、LF 都可结束一行；
- 空行触发一个事件派发；
- `data:` 可以出现多次，值以换行拼接；
- `event:` 指定类型，缺省为 `message`；
- `id:` 更新 last event ID；
- `retry:` 只在值全为数字时更新重连间隔；
- `:` 开头是注释，不能当业务数据。

来源：[WHATWG：Server-sent events](https://html.spec.whatwg.org/multipage/server-sent-events.html)

因此生产实现不能假定：

- 一次 `read(byte[])` 等于一个事件；
- 一行 `data:` 永远是完整事件；
- 每个事件都只有一条 `data:`；
- 只要 TCP 有字节就是首个业务 token；
- 所有 Provider 都只会返回文本 delta。

OpenAI 当前的 Responses 流会先产生响应生命周期事件，再产生诸如 `response.output_text.delta`、工具参数增量、完成或错误事件；新增事件类型也被官方列为向后兼容变更。内部解析器应对未知事件采取可诊断的前向兼容策略，而不是遇到新事件直接把整个流打崩：

- [OpenAI：Streaming events](https://platform.openai.com/docs/api-reference/responses-streaming)
- [OpenAI：API backwards compatibility](https://developers.openai.com/api/reference/overview#backwards-compatibility)

### 5.2 首包的三个时刻

建议内部明确三个指标：

| 时刻 | 定义 | 用途 |
|---|---|---|
| `firstUpstreamEventAt` | 解析出第一个合法上游 SSE 事件 | 网络/协议健康 |
| `firstMeaningfulChunkAt` | 出现可交付的文本、工具参数、拒答增量等业务事件 | 模型实际开始产出 |
| `downstreamCommittedAt` | 第一条业务事件真正交给下游 | 故障转移安全边界、用户感知延迟 |

OpenTelemetry GenAI 语义约定已定义 `gen_ai.response.time_to_first_chunk`，其测量范围是发出生成请求到收到第一个流式 chunk；xrag 可以沿用该指标名对应上游首块，同时额外记录内部探测缓冲造成的提交延迟。[OpenTelemetry GenAI：Inference spans](https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-spans.md#inference)

### 5.3 探测缓冲与提交点

建议的流式尝试状态：

```text
CONNECTING
  → PROBING（合法事件先进入有界缓冲）
      ├─ 首个有意义事件在时限内到达 → COMMITTED → 向下游排空缓冲并继续转发
      ├─ 上游错误 / 首包超时         → 丢弃缓冲，记录失败，尝试下一候选
      └─ 调用方取消                 → CANCELLED，释放上游
  → COMMITTED
      ├─ 正常完成 → COMPLETED
      ├─ 上游中断 → STREAM_FAILED（不混入另一模型）
      └─ 调用方取消 → CANCELLED
```

缓冲必须有事件数/字节数上限。首个有意义事件不能只定义为“非空文本”，因为模型可能先输出工具调用参数或明确拒答；具体成功事件集合由能力契约定义。

Envoy 把 `upstream_reset_before_response_started` 和 `upstream_reset_after_response_started` 作为不同终止原因，并单独统计“下游响应已开始后的 reset”。这为上述提交点边界提供了成熟代理实现的旁证。[Envoy：Response Code Details](https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_conn_man/response_code_details)

### 5.4 取消、背压和资源释放

Reactive Streams 的正式规范要求：

- Publisher 不得发送超过 Subscriber 已请求的元素数；
- 取消后 Publisher 最终停止发送；
- Subscriber 不再需要订阅时必须取消；
- `cancel` 必须幂等、线程安全并及时返回；
- `onError` / `onComplete` 后不得再发送事件。

来源：[Reactive Streams JVM Specification](https://github.com/reactive-streams/reactive-streams-jvm)

对 xrag 的验收含义：

- 浏览器断开、用户取消、首包超时、总超时和服务关闭都要传播到上游 HTTP 请求；
- 每条终止路径都要释放连接、订阅、探测缓冲、熔断半开许可和并发许可；
- 事件缓冲必须有界，不能把慢下游变成无限内存队列；
- 取消不记录为 Provider 失败；
- 允许取消信号传播存在短暂延迟，测试不能错误要求“调用 cancel 后绝不再收到任何竞态中的事件”，但最终必须停止并释放。

Reactor Netty 还把连接池获取、连接、响应等超时区分为不同配置，并建议优先使用具体超时而不是只套一个覆盖全流程的 `timeout`。Phase 2 应至少区分连接超时、首个业务事件超时、流空闲超时和逻辑调用总截止时间。[Reactor Netty：HTTP Client Timeout Configuration](https://projectreactor.io/docs/netty/release/reference/http-client.html#timeout-configuration)

## 6. Embedding 客户端与 Phase 1 迁移

### 6.1 当前可直接保留的契约

xrag 已有的 [EmbeddingClient.java](../../backend/src/main/java/com/app/knowledge/embedding/EmbeddingClient.java) 定义：

- 批量文本输入；
- 返回与输入数量、顺序严格一致；
- 显式暴露向量维度；
- 外部 HTTP 调用不得位于数据库事务内。

这些正好对应官方 Embeddings 协议和 Phase 1 的数据库不变量，应保留为知识库模块依赖的稳定端口，而不是让知识库直接依赖新的路由实现。

现有 [OpenAiCompatibleEmbeddingClient.java](../../backend/src/main/java/com/app/knowledge/embedding/OpenAiCompatibleEmbeddingClient.java) 已做：

- 串行分批；
- 按响应 `index` 重排；
- 条数、index、维度校验；
- 429 指数退避；
- 中断标志恢复；
- 错误体保留。

这些都应在迁移后保持自动化回归。

### 6.2 需要交给 Phase 2 统一处理的部分

- Provider / 模型 / 能力注册，不再只有一套全局 `app.embedding.*`；
- 候选路由、熔断和故障转移；
- `Retry-After`、抖动和共享总尝试预算；
- Provider 请求 ID、限流余量和调度诊断；
- Provider 级批次上限、token 上限、维度能力；
- 合规 Key 与 Provider 准入。

OpenAI 官方接口当前同时限制单输入 token、批量输入数组长度和一次请求合计 token，并且 `dimensions` 只适用于部分模型。这些都证明批次能力必须属于具体模型/Provider 元数据，不能把当前 Ark 的“每批 10 条”写成统一协议规则。[OpenAI：Create embeddings](https://developers.openai.com/api/reference/resources/embeddings/methods/create)

### 6.3 迁移边界

推荐的兼容方向：

```text
knowledge 模块
  └─ 继续依赖 EmbeddingClient
         └─ Phase 2 提供 RoutedEmbeddingClient
                └─ 调度骨架 + Provider adapters
```

第一版不需要同时保留两套生产 Embedding 实现；现有实现中的协议编解码可以迁入 Provider 适配器，知识库调用点和测试替身继续面向 `EmbeddingClient`。

## 7. Rerank 客户端

Cohere 官方 Rerank API 的关键行为：

- 输入 query 和候选文档列表；
- 返回按相关性排序的结果；
- 每个结果带原文档 `index` 和 `relevance_score`；
- `top_n` 只限制返回数量；
- 长文档可能按 `max_tokens_per_doc` 截断；
- 官方不建议单次超过 1,000 个文档；
- 响应还包含计费单位和请求 ID。

来源：[Cohere：Rerank API v2](https://docs.cohere.com/v2/reference/rerank)

对 xrag 的最低契约建议：

- 入参不是裸 `List<String>`，而是稳定候选 ID + 可发送文本；
- 出参引用候选 ID，同时保留 Provider 的原始 index 供诊断；
- 校验越界、重复 index 和 top-N；
- 截断策略要显式，不能让 Provider 静默截断后看起来像完整文档排序；
- 相关性分数只在同一模型/同一次调用内用于排序，不应默认跨模型比较；
- Provider 不支持 Rerank 时，路由层应明确“无候选”，不能偷偷拿 Embedding 余弦相似度伪装成同一种能力。

## 8. 可观测性与轻量诊断页

### 8.1 推荐的逻辑调用与尝试层级

```text
logical_call
  ├─ route_decision
  ├─ attempt #1 providerA/modelX
  │    ├─ retry #1（如有）
  │    └─ outcome
  ├─ attempt #2 providerB/modelY（如故障转移）
  └─ final_outcome
```

逻辑调用记录最终用户可见结果；attempt 记录每个实际上游请求。否则“最终成功”会掩盖主 Provider 已经连续失败的事实。

### 8.2 建议字段

| 类别 | 字段 |
|---|---|
| 身份 | internal request/trace ID、Provider request ID、attempt number |
| 路由 | capability、candidate set、rejection reason、selected provider/model、policy version |
| 熔断 | key、before/after state、permission、transition reason |
| 时间 | route duration、connect/headers、first upstream event、first meaningful chunk、downstream commit、total |
| 结果 | success/error/cancel、normalized error type、HTTP status、finish reason |
| 用量 | input/output token、Embedding input count、Rerank document count、计费单位（若有） |
| 流式 | stream flag、committed flag、chunk count、termination reason |

OpenTelemetry GenAI 当前约定的可复用属性包括：

- `gen_ai.operation.name`
- `gen_ai.provider.name`
- `gen_ai.request.model`
- `gen_ai.response.model`
- `gen_ai.request.stream`
- `gen_ai.response.finish_reasons`
- `gen_ai.response.time_to_first_chunk`
- `gen_ai.usage.input_tokens`
- `gen_ai.usage.output_tokens`
- `error.type`

其约定还建议一个逻辑 span 覆盖自动重试后的完整操作时长。来源：[OpenTelemetry GenAI：Inference spans](https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-spans.md#inference)。

OpenAI 官方建议生产环境记录 `x-request-id`，并允许客户端发送自己的 `X-Client-Request-Id`；响应还可能提供限流上限、余量和重置时间。Provider 适配器应把这类头归一为诊断元数据，但不要假定所有供应商都使用相同头名。[OpenAI：Debugging requests](https://developers.openai.com/api/reference/overview#debugging-requests)

### 8.3 内容与凭据安全

OpenTelemetry 把输入消息、输出消息和系统指令列为 opt-in 属性，并明确警告它们可能包含用户数据或 PII。轻量诊断页默认不应保存或展示：

- API Key / Authorization；
- 完整 Prompt、文档内容或模型输出；
- 工具调用中的敏感参数；
- 原始 Provider 错误体中的凭据或用户数据。

第一版可只存模型调度事实、低基数错误分类、长度/token 数和脱敏请求 ID。若未来需要采样正文，必须在 PRD/技术方案中另行定义开关、脱敏、权限和保留期。

## 9. 与 xrag 当前实现的逐项映射

| 当前事实 | 证据 | Step A / 后续含义 |
|---|---|---|
| 后端是 Java 17、Spring Boot 3.4.1、`spring-boot-starter-web`，没有 WebFlux/Resilience4j/OpenTelemetry 依赖 | [pom.xml](../../backend/pom.xml) | 不要在学习稿里把任何具体库写成既成事实；Step C 再做依赖选择 |
| 当前只有 Embedding AI 客户端，无 Chat / 流式 Chat / Rerank | [embedding 包](../../backend/src/main/java/com/app/knowledge/embedding) | Phase 2 是新增共享基础设施并迁移已有能力，不是重构现成调度系统 |
| Embedding 使用同步 `RestClient` | [OpenAiCompatibleEmbeddingClient.java](../../backend/src/main/java/com/app/knowledge/embedding/OpenAiCompatibleEmbeddingClient.java) | 同步能力可保留；流式能力需单独决定 HTTP/异步模型 |
| Embedding 只对 429 重试，固定倍增，无 `Retry-After` 和抖动 | [OpenAiCompatibleEmbeddingClient.java](../../backend/src/main/java/com/app/knowledge/embedding/OpenAiCompatibleEmbeddingClient.java) | 作为共享重试策略的现实输入，但不能原样推广给所有能力 |
| 现有 Embedding 配置全局唯一，Ark 每批 10 条是已验证的供应商限制 | [application.properties](../../backend/src/main/resources/application.properties) | 批次、维度、端点和能力应迁为 Provider/模型元数据 |
| 知识库创建时固化 embedding model/dimensions，数据库列为 `vector(1024)` | [knowledge-base architecture](../../tech/knowledge-base/architecture.md#6-embedding-接入prd-9-待决策-4) | 路由候选必须保证向量空间兼容；故障转移不能仅看维度相同 |
| Embedding 外部调用位于事务外，并有测试断言 | [EmbeddingClient.java](../../backend/src/main/java/com/app/knowledge/embedding/EmbeddingClient.java)、[FakeEmbeddingConfig.java](../../backend/src/test/java/com/app/knowledge/FakeEmbeddingConfig.java) | 迁移必须保持；调度诊断写库也不能把外部模型调用包进长事务 |
| 当前部署决策是单实例；进程内并发协调在此假设下可接受 | [ADR 0002](../adr/0002-knowledge-base-async-and-concurrency.md) | 进程内熔断可作为第一版候选，但必须记录多实例失效点 |
| 当前真实 Provider 测试由环境变量守门，CI 使用确定性假实现 | [RealEmbeddingApiTests.java](../../backend/src/test/java/com/app/knowledge/embedding/RealEmbeddingApiTests.java) | Phase 2 应延续“假 Provider 进 CI、真实能力显式守门”的测试模式 |
| 现有 Ark Coding Plan Key 用法不合规，项目已记录知情风险 | [knowledge-base architecture](../../tech/knowledge-base/architecture.md#6-embedding-接入prd-9-待决策-4) | Phase 2 Provider 准入必须使用合规 Key，不能把现状扩展到 Chat/Rerank |

## 10. 对八篇 Step A 学习稿的具体支撑

| 学习稿 | 应吸收的一手资料结论 | 应重点对照 xrag |
|---|---|---|
| 04-01 AI 基础设施层宏观设计 | 能力端口分开、调度骨架共享；OpenAI-compatible 是协议子集而非行为等价 | 当前只有 `knowledge.embedding`；明确迁移而非并存 |
| 04-02 多模型路由与智能选择 | 硬能力过滤优先，确定性候选链作为第一版；学习型路由依赖评测数据 | Phase 4 前不做无证据的 LLM 判路由 |
| 04-03 三态熔断器与故障转移 | 滑动窗口、最小样本、失败/慢调用分类、半开许可、状态键隔离 | 单实例可先内存态；Provider+模型+能力隔离 |
| 04-04 Chat 同步调用与模板方法 | 同步调用共享尝试模板，但能力请求/响应和错误适配独立 | 当前 `RestClient` 经验可复用，不能复用 Embedding DTO |
| 04-05 SSE 流式解析与异步执行 | WHATWG 事件语法、UTF-8、多 data 行、未知事件、取消和背压 | 当前没有流式栈；不要假设“线程池 + readLine”已满足取消语义 |
| 04-06 流式路由的首包探测机制 | 首事件/首业务块/下游提交分离；提交前缓冲可切换，提交后不混流 | 记录三类耗时和有界缓冲；对工具增量/拒答也定义“有意义” |
| 04-07 Embedding 向量化客户端 | 批量限制属于 Provider；按 index 还原；维度和向量空间不变量 | 保留 Phase 1 接口、事务外调用和回归测试 |
| 04-08 Rerank 重排序与辅助工具 | query+documents+top_n、index+score、截断与计费元数据 | 候选用稳定 ID；不把 Embedding 相似度伪装成 Rerank |

## 11. Step B / Step C 必须继续决策的问题

### 产品与契约

1. 调用方请求的是具体模型，还是能力/质量等级，由路由器选择模型？
2. 同步与流式失败时，哪些错误允许自动换 Provider，哪些必须直接暴露？
3. 流已提交后的中断，前端展示部分结果加错误，还是丢弃部分结果？
4. Provider/模型配置是文件配置、运行时只读，还是支持页面 CRUD？
5. 轻量诊断页的记录保留期、查看权限和脱敏规则是什么？

### 技术

1. 流式客户端采用 `WebClient` / Reactor，还是阻塞式 HTTP + 明确线程和取消封装？
2. 熔断用成熟库还是自研；如果用库，如何为流式调用在提交点后正确记录结果？
3. 熔断 key 到底是 Provider、端点、模型、能力还是它们的组合？
4. 429 是同候选等待重试、直接故障转移，还是取决于剩余总截止时间？
5. 首个有意义事件对文本、工具调用、结构化输出、拒答分别如何定义？
6. 流式探测缓冲的事件数、字节数和时间上限是多少？
7. 是否持久化调度记录；若持久化，写入失败是否影响主调用？
8. Phase 1 已生成向量的模型/版本身份如何与新路由候选绑定，避免跨向量空间故障转移？

## 12. 一手来源清单

- [OpenAI API：Create chat completion](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create)
- [OpenAI API：Create embeddings](https://developers.openai.com/api/reference/resources/embeddings/methods/create)
- [OpenAI API：Streaming events](https://platform.openai.com/docs/api-reference/responses-streaming)
- [OpenAI API：Debugging requests / request IDs](https://developers.openai.com/api/reference/overview#debugging-requests)
- [Cohere API：Rerank v2](https://docs.cohere.com/v2/reference/rerank)
- [WHATWG HTML：Server-sent events](https://html.spec.whatwg.org/multipage/server-sent-events.html)
- [Reactive Streams JVM Specification](https://github.com/reactive-streams/reactive-streams-jvm)
- [Spring Framework 6.2：REST Clients](https://docs.spring.io/spring-framework/reference/6.2/integration/rest-clients.html)
- [Reactor Netty：HTTP Client / Timeout Configuration](https://projectreactor.io/docs/netty/release/reference/http-client.html#timeout-configuration)
- [Resilience4j：CircuitBreaker](https://resilience4j.readme.io/docs/circuitbreaker)
- [RFC 6585：429 Too Many Requests](https://www.rfc-editor.org/rfc/rfc6585.html#section-4)
- [RFC 9110：Retry-After](https://www.rfc-editor.org/rfc/rfc9110.html#name-retry-after)
- [Envoy：Router / retries](https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_filters/router_filter.html)
- [Envoy：Response Code Details](https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_conn_man/response_code_details)
- [Envoy AI Gateway：Provider Fallback](https://aigateway.envoyproxy.io/docs/capabilities/traffic/provider-fallback/)
- [Envoy AI Gateway：Inference Optimization](https://aigateway.envoyproxy.io/docs/capabilities/inference/)
- [Envoy AI Gateway：Supported API Endpoints](https://aigateway.envoyproxy.io/docs/0.3/capabilities/llm-integrations/supported-endpoints/)
- [OpenTelemetry：GenAI Inference Spans](https://github.com/open-telemetry/semantic-conventions-genai/blob/main/docs/gen-ai/gen-ai-spans.md#inference)
- [RouteLLM：Learning to Route LLMs with Preference Data](https://arxiv.org/abs/2406.18665)
