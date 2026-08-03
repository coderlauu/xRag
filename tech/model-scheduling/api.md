# API 契约：大模型调度引擎

- `status`: approved（2026-08-02）
- `date`: 2026-07-28
- `architecture`: [技术方案](architecture.md)
- `data_model`: [数据模型](data-model.md)
- `ui`: [界面规格](ui-spec.md)

本文同时冻结两类契约：

1. Phase 3 / Phase 1 在进程内调用的 Java 能力端口；
2. `/diagnostics` 页面使用的只读 HTTP API。

Phase 2 不提供面向最终用户的 `/chat/completions` 或模型代理 HTTP API。未来问答端点由 Phase 3 拥有，避免基础设施层顺手变成第二套聊天产品。

## 1. Java 能力契约

### 1.1 通用调用选项

```java
public record RequestId(String value) {}
public record ProviderId(String value) {}
public record DeploymentId(String value) {}
public record ModelIdentity(String value) {}
public record DataRegion(String value) {}

public enum ServiceTier {
    ECONOMY,
    BALANCED,
    QUALITY
}

public record CallOptions(
        RequestId requestId,
        Duration timeout,
        CancellationToken cancellation,
        Set<DataRegion> allowedDataRegions) {
}
```

规则：

- `requestId` 为空时由调度器生成；它用于关联，不承担唯一主键。
- `timeout` 为空使用能力默认值；调用方只能缩短，不能超过配置上限。
- 同步调用在每个边界检查 `CancellationToken` 和线程中断，当前 Provider request 还必须注册取消回调以主动 abort；流式调用以订阅取消为最终准则。
- `allowedDataRegions` 为空时使用应用级默认边界。
- 普通选项里没有 Provider/model selector。

`CancellationToken` 支持轮询和一次性回调注册：

```java
public interface CancellationToken {
    boolean isCancelled();

    CancellationRegistration onCancel(Runnable listener);

    static CancellationToken none() {
        return new CancellationToken() {
            @Override
            public boolean isCancelled() {
                return false;
            }

            @Override
            public CancellationRegistration onCancel(Runnable listener) {
                return () -> {};
            }
        };
    }
}

@FunctionalInterface
public interface CancellationRegistration extends AutoCloseable {
    @Override
    void close();
}
```

若注册时 token 已取消，listener 必须在 `onCancel` 返回前恰好执行一次；否则在首次取消时执行一次。Provider attempt 用 registration 绑定当前 HTTP request 的 abort，并在 attempt 结束时注销；线程中断映射为取消后必须恢复中断标志。该接口不向业务层暴露具体 HTTP 客户端。

### 1.2 共用执行元数据

Chat、流式 Chat 和 Rerank 的成功或显式降级结果携带：

```java
public record ExecutionTarget(
        ProviderId providerId,
        DeploymentId deploymentId,
        ModelIdentity modelIdentity) {
}

public record ExecutionInfo(
        UUID logicalCallId,
        RequestId requestId,
        ExecutionTarget actualExecution,
        int attemptCount,
        int retryCount,
        int failoverCount) {
}
```

`actualExecution` 只表示唯一贡献成功结果的 deployment，不表示“最后尝试的 deployment”。失败和 Rerank Noop 降级时它为 `null`；失败的终态 attempt 见异常中的 `TerminalCause`。Embedding 可能由多个 deployment 分批完成，使用能力专属的 `EmbeddingExecutionInfo`，不把多值硬塞进这个共用结构。

三个计数只描述已经发起的真实 Provider 请求：

- `attemptCount` 是该 logical call 的 `ai_attempt` 行数；
- `retryCount` 是其中 `attemptKind=RETRY` 的行数；
- `failoverCount` 是其中 `attemptKind=FAILOVER` 的行数，`failoverOccurred` 当且仅当该值大于 0。

breaker 许可拒绝、预算不足，或只选中候选但尚未 outbound dispatch 就取消，均只写 route event，不增加这些计数。每个 work unit 的首个实际请求通常为 `INITIAL`；同候选再次 dispatch 为 `RETRY`；运行时拒绝或失败后向另一候选首次 dispatch 为 `FAILOVER`。

### 1.3 Chat

```java
public interface ChatClient {
    ChatResult complete(ChatCommand command);
}

public record ChatCommand(
        List<ChatMessage> messages,
        List<ToolDefinition> tools,
        ServiceTier tier,
        CallOptions options) {
}

public sealed interface ChatMessage
        permits SystemMessage, UserMessage, AssistantMessage, ToolResultMessage {
}

public record SystemMessage(String text) implements ChatMessage {}

public record UserMessage(String text) implements ChatMessage {}

public record AssistantMessage(
        String text,
        String refusal,
        List<ToolCall> toolCalls) implements ChatMessage {
}

public record ToolResultMessage(
        String toolCallId,
        String text) implements ChatMessage {
}

public record ToolCall(
        String id,
        String name,
        String argumentsJson) {
}

public record ToolDefinition(
        String name,
        String description,
        String inputSchemaJson) {
}

public enum ChatFinishReason {
    STOP,
    LENGTH,
    TOOL_CALLS,
    CONTENT_FILTER,
    UNKNOWN
}

public record TokenUsage(
        long inputTokens,
        long outputTokens,
        long totalTokens) {
}

public record ChatResult(
        AssistantMessage message,
        ChatFinishReason finishReason,
        TokenUsage usage,
        ExecutionInfo execution) {
}
```

最低校验：

- `messages` 非空，所有列表在构造时复制且对外不可变；`tools` 为空时使用空列表而不是 null；
- system/user 文本非空；assistant 至少包含非空 text、refusal 或一个 tool call；tool result 必须引用非空 tool-call ID；
- `ToolCall.id/name` 非空，`argumentsJson` 保存完整参数 JSON 文本；`ToolDefinition.name` 非空，`inputSchemaJson` 必须是 JSON object；
- `tier` 为空时默认 `BALANCED`；
- 调度模块从 `tools` 非空、assistant 历史含 tool call、或存在 tool-result message 自动推导内部 `TOOL_CALLING` 要求；公共命令不提供可漏填或关闭它的 `requiredFeatures` 开关；
- Provider 实际响应模型只写进 `ExecutionInfo`，不覆盖请求约束；
- Provider finish reason 在 adapter 内归一为 `ChatFinishReason`，未知值映射 `UNKNOWN`，原始字符串不越过 seam；
- `TokenUsage` 三个值非负且 `totalTokens=inputTokens+outputTokens`；Provider 没有返回完整 usage 时整个对象为 null，不能用 0 补缺失值。

Phase 2 只冻结 Phase 3 已知消费者需要的纯文本系统、用户、助手、工具结果消息，以及工具定义/工具增量的传输结构。它只验证工具定义和参数字段的传输形状，不选择或执行工具，也不解释参数业务语义；流式参数组装与最终校验属于 Phase 3。多模态消息、developer role、tool choice、parallel/strict tool calling、结构化输出和其他没有真实消费者的能力递延。

### 1.4 流式 Chat

```java
public interface StreamingChatClient {
    Flux<ChatStreamEvent> stream(ChatCommand command);
}

public sealed interface ChatStreamEvent {
    record StreamStarted(ExecutionInfo execution) implements ChatStreamEvent {}
    record TextDelta(String text) implements ChatStreamEvent {}
    record ToolCallDelta(String callId, int index, String name, String argumentsFragment)
            implements ChatStreamEvent {}
    record RefusalDelta(String text) implements ChatStreamEvent {}
    record Completed(ChatFinishReason finishReason, TokenUsage usage) implements ChatStreamEvent {}
    record StreamFailed(TerminalCause cause, String message, boolean incomplete)
            implements ChatStreamEvent {}
}
```

事件规则：

1. 订阅前不发起 HTTP 请求。
2. 每次 `stream(command)` 返回一个 single-subscription publisher。首次订阅创建一次逻辑调用；对同一对象二次订阅以 `IllegalStateException` 结束，不创建第二个调用。调用方要发起新调用必须重新调用 `stream`，不得对返回值使用 `retry`、`repeat`、`cache` 或 `share`。
3. 第一条下游事件必须是 `StreamStarted`，且只在首个有效业务块已缓冲后发出。
4. 若全部候选在提交前失败，publisher 直接 `onError(ModelCallException)`；没有任何 `ChatStreamEvent`。
5. `StreamStarted` 发出后视为已提交，不再切换模型。
6. 提交后失败时发送唯一 `StreamFailed(cause, message, true)`，随后正常完成 publisher，不再用 reactive `onError` 传递同一故障。
7. `Completed`、`StreamFailed` 和取消互斥；流内最多出现一个显式终态。
8. 取消不发送伪造的 `StreamFailed`，由 Reactive Streams cancel 终止。
9. `TokenUsage` 可为 null；供应商在断流时没返回 usage 不算协议错误。

同一工具调用的 `index` 从 0 开始且在流内稳定；adapter 在首次下发该 index 前必须已经取得非空 `callId/name`，后续事件重复同一值。`argumentsFragment` 按上游顺序传递，允许单片不是完整 JSON；Phase 2 不拼装或解析完整参数，Phase 3 在消费 `Completed` 前负责组装并验证。

### 1.5 Embedding

Embedding 使用一个命令式深接口；身份、结果校验和执行事实不能被兼容重载绕过：

```java
public interface EmbeddingClient {
    EmbeddingResult embed(EmbeddingCommand command);
}

public record EmbeddingCommand(
        ModelIdentity modelIdentity,
        List<String> texts,
        CallOptions options) {
}

public record EmbeddingResult(
        List<float[]> vectors,
        ModelIdentity modelIdentity,
        int dimensions,
        EmbeddingExecutionInfo execution) {
}

public record EmbeddingExecutionInfo(
        UUID logicalCallId,
        RequestId requestId,
        int attemptCount,
        int retryCount,
        int failoverCount,
        boolean mixedExecution,
        List<ExecutionTarget> contributingDeployments,
        List<EmbeddingBatchExecution> batches) {
}

public record EmbeddingBatchExecution(
        int workUnitIndex,
        int inputFromInclusive,
        int inputToExclusive,
        ExecutionTarget actualExecution,
        int attemptCount,
        int retryCount,
        int failoverCount) {
}
```

契约：

- `modelIdentity` 和 `texts` 不得为 null；空列表返回空列表且不创建 Provider attempt；
- 返回数量和顺序与输入严格一致；
- `EmbeddingResult.modelIdentity` 必须等于请求身份；所有向量维度等于结果和 catalog 固定的 dimensions；
- 向量不得包含 `NaN` 或无穷；
- 精确身份无候选时直接失败，不回退到默认身份；
- 每个 batch 只记录真正贡献该批结果的 `actualExecution`；失败 attempt 仍由总 attempt 计数和诊断详情表达；
- `contributingDeployments` 按首次成功贡献的批次顺序去重；`mixedExecution` 当且仅当其数量大于 1；
- mixed execution 仅允许 catalog 明确证明 artifact/version、预处理 fingerprint 和 dimensions 等价的 deployment；相同维度本身不是等价证明；
- 任一批最终失败，或完整结果返回前被取消时，都不返回部分 vectors，而是抛 `ModelCallException`；该 logical call 的 contributing count 为 0、mixed 为 false、调用级 actual 为空，已经完成的 batch 只作为成功 attempt 保留在诊断详情。

迁移是一次原子源码更新：同一工单替换现有三个实现、测试 fake/匿名实现、Bean wiring、启动维度校验和全部知识库调用点。提交边界前后都必须可编译；不保留 `embed(List)`、`dimensions()` 或裸字符串身份重载。模型维度由 catalog/profile 在启动校验时提供，不能通过默认模型端口旁路身份。

### 1.6 Rerank

```java
public interface RerankClient {
    RerankResult rerank(RerankCommand command);
}

public record RerankCommand(
        String query,
        List<RerankCandidate> candidates,
        int topN,
        ServiceTier tier,
        CallOptions options) {
}

public record RerankCandidate(String id, String text) {}

public record RankedCandidate(String id, int originalIndex, Double relevanceScore) {}

public enum RerankDegradationReason {
    CAPABILITY_NOT_DECLARED,
    NO_ELIGIBLE_CANDIDATE,
    RECOVERABLE_CANDIDATES_EXHAUSTED
}

public record RerankResult(
        List<RankedCandidate> items,
        boolean rerankApplied,
        RerankDegradationReason degradationReason,
        ExecutionInfo execution) {
}
```

规则：

- candidate ID 非空且在一次请求内唯一；
- `1 <= topN <= candidates.size()`；
- `tier` 为空时默认 `BALANCED`；
- 真实 Provider 结果校验 index 越界、重复和数量；
- `relevanceScore` 只在真实 Rerank 时存在；
- Noop 保持原顺序截取 top-N，`rerankApplied=false`、score 为 null，并返回稳定 `degradationReason`；
- `rerankApplied=true` 当且仅当 `degradationReason=null`；`rerankApplied=false` 时 reason 必须非空；
- Noop 的 `execution.actualExecution` 为 null、attempt 数为 0 或保留此前失败 attempt 数，不伪造 Provider 成功。

## 2. 统一错误

```java
public enum ModelErrorType {
    INVALID_REQUEST,
    NO_ELIGIBLE_MODEL,
    AUTHENTICATION,
    PERMISSION,
    RATE_LIMITED,
    NETWORK,
    SERVER_ERROR,
    TIMEOUT,
    PROTOCOL_CONTRACT,
    CANCELED,
    STREAM_INTERRUPTED
}
```

失败阶段与“为什么停止恢复”是两个独立维度：

```java
public enum FailurePhase {
    CONNECT,
    RESPONSE_HEADERS,
    UPSTREAM_RESPONSE,
    FIRST_UPSTREAM_EVENT,
    FIRST_MEANINGFUL_CHUNK,
    STREAM_IDLE,
    UPSTREAM_STREAM,
    PROVIDER_RESPONSE_VALIDATION,
    TOTAL_DEADLINE
}

public enum TerminationReason {
    COMPLETED,
    NOOP_DEGRADED,
    INVALID_REQUEST,
    NO_ELIGIBLE_MODEL,
    NON_RECOVERABLE_ERROR,
    ALL_CANDIDATES_EXHAUSTED,
    DEADLINE_EXCEEDED,
    CALLER_CANCELED,
    STREAM_INTERRUPTED_AFTER_COMMIT
}

public record TerminalCause(
        ModelErrorType errorType,
        FailurePhase failurePhase,
        TerminationReason terminationReason,
        Integer httpStatus,
        ExecutionTarget attemptedExecution) {
}

public final class ModelCallException extends RuntimeException {
    private final ExecutionInfo execution;
    private final TerminalCause terminalCause;
    private final boolean retryableByCaller;

    public ModelCallException(
            String message,
            ExecutionInfo execution,
            TerminalCause terminalCause,
            boolean retryableByCaller) {
        super(message);
        this.execution = java.util.Objects.requireNonNull(execution);
        this.terminalCause = java.util.Objects.requireNonNull(terminalCause);
        this.retryableByCaller = retryableByCaller;
    }

    public ExecutionInfo execution() {
        return execution;
    }

    public TerminalCause terminalCause() {
        return terminalCause;
    }

    public boolean retryableByCaller() {
        return retryableByCaller;
    }
}
```

`failurePhase` 只描述 Provider attempt 或总 deadline 的失败位置。参数校验、无候选和 attempt 前取消没有对应阶段，允许为 `null`。有限响应已收到 headers、但 body 尚未完整读完时发生的 I/O 失败或超时使用 `UPSTREAM_RESPONSE`；响应已经读完但格式、索引、维度或字段不合法，以及畸形流事件，使用 `PROVIDER_RESPONSE_VALIDATION`。合法 SSE 中由 Provider 明确声明的错误，以及流提交后的非 idle 上游中断使用 `UPSTREAM_STREAM`。内部细分错误到 `ModelErrorType + FailurePhase` 的唯一映射由架构错误动作表冻结，adapter 不得各自发明外部类型。候选耗尽不是新的根因错误类型：`TerminalCause.errorType` 保留最后一个真实错误，`terminationReason=ALL_CANDIDATES_EXHAUSTED` 单独解释调度器为何停止。

流式停止原因按以下优先级冻结：调用方取消在提交前后始终是 `CALLER_CANCELED`；一旦已经提交，任何非取消失败（包括总 deadline、idle timeout、Provider error event、协议错误和 reset）都是 `STREAM_INTERRUPTED_AFTER_COMMIT`，但保留具体 `errorType + failurePhase`；未提交时总 deadline 到期才是 `DEADLINE_EXCEEDED`。

`ModelCallException` 对业务层只提供：

- `ExecutionInfo`（逻辑调用 ID、request ID、attempt/retry/failover 事实；失败时 `actualExecution` 为 null）
- `TerminalCause`（稳定错误类型、失败阶段、停止原因、可选 HTTP status 和终态 attempt 目标）
- 稳定中文 message
- `retryableByCaller`（调度器已经耗尽内部恢复动作后才返回）

不提供 `rawBody`、Authorization、完整 URL、供应商异常实例或未脱敏请求 ID。

## 3. Provider SPI 契约

Provider SPI 位于 `com.app.ai.provider`，只对调度模块可见。四个接口签名见 [architecture.md §5.2](architecture.md#52-provider-spi)。

`ProviderAttemptContext` 至少包含：

```java
record ProviderAttemptContext(
        UUID logicalCallId,
        int attemptNo,
        Deadline deadline,
        CancellationToken cancellation) {
}

interface Deadline {
    Duration remaining();
    boolean isExpired();
}
```

`Deadline` 由调度器的单调时钟创建，所有 retry、等待、failover 与 transport 共享同一实例；不能把 wall-clock `Instant` 重新换算成另一套时钟。Provider 在每次请求前把连接、响应头和 body/流式读取超时收紧为剩余总预算与该阶段上限的较小值。取消回调必须 abort 当前 request。

适配器责任：

- 根据 deployment 组装 URL、模型名、认证和供应商参数；
- 把 HTTP/SDK 错误映射为统一错误；
- 提取并脱敏 Provider request ID；
- 返回 usage、finish reason 和限流元数据；
- 流式适配器把供应商事件转为 `ProviderChatEvent`；
- Embedding/Rerank 校验供应商 response index 的协议形状。

调度器责任：

- 候选、预算、重试、breaker、failover；
- 不读取原始错误体；
- 不为适配器猜测能力。

## 4. HTTP 通用约定

### 4.1 路径与响应

- 基础路径：`/api/v1/ai/diagnostics`
- JSON 字段：camelCase
- 时间：ISO 8601，带时区
- 分页：`page` 从 1 开始，默认 20；`size` 默认 20、最大 100
- 分页形状：`{items,page,size,total}`

### 4.2 错误响应

沿用现有形状：

```json
{
  "error": "DIAGNOSTIC_CALL_NOT_FOUND",
  "message": "未找到这条模型调用记录；它可能已超过保留期。"
}
```

| HTTP | error | 场景 |
|---:|---|---|
| 400 | `INVALID_FILTER` | 枚举、页码或时间范围非法 |
| 404 | `DIAGNOSTIC_CALL_NOT_FOUND` | 详情不存在或已清理 |
| 503 | `DIAGNOSTIC_STORE_UNAVAILABLE` | 查询数据库失败 |

主模型调用不会因为 `DIAGNOSTIC_STORE_UNAVAILABLE` 失败；这个状态只影响诊断页面查询。

## 5. 调度总览

### `GET /api/v1/ai/diagnostics/overview`

返回当前配置快照、当前 JVM breaker 和最近 24 小时聚合：

```json
{
  "generatedAt": "2026-07-28T17:30:00+08:00",
  "policyVersion": "phase2-v1",
  "configFingerprint": "7b6a42e2e7c4…",
  "retention": {
    "maxAgeDays": 7,
    "maxLogicalCalls": 10000,
    "oldestAvailableAt": "2026-07-27T09:12:00+08:00"
  },
  "summary": {
    "eligibleCandidates": 3,
    "openBreakers": 1,
    "failovers24h": 8,
    "failedOrInterrupted24h": 2,
    "diagnosticsDroppedSinceStart": 0,
    "diagnosticWriteFailuresSinceStart": 0
  },
  "deployments": [
    {
      "providerId": "aliyun-model-studio",
      "providerDisplayName": "阿里云百炼",
      "deploymentId": "aliyun-qwen-plus-cn",
      "modelIdentity": "qwen-plus-2025-12-01",
      "capabilities": ["CHAT", "STREAM_CHAT"],
      "tiers": ["BALANCED"],
      "dataRegion": "CN_BEIJING",
      "enabled": true,
      "configurationStatus": "CONFIGURED",
      "verifiedCapabilities": ["CHAT", "STREAM_CHAT"],
      "limits": {
        "maxContextTokens": 131072
      }
    }
  ],
  "breakers": [
    {
      "breakerKey": "aliyun-model-studio/aliyun-qwen-plus-cn/CHAT",
      "providerId": "aliyun-model-studio",
      "deploymentId": "aliyun-qwen-plus-cn",
      "modelIdentity": "qwen-plus-2025-12-01",
      "capability": "CHAT",
      "state": "OPEN",
      "allowsCall": false,
      "halfOpenPermissionInUse": false,
      "lastChangedAt": "2026-07-28T17:25:00+08:00",
      "lastChangeReason": "FAILURE_RATE",
      "recent": {
        "success": 7,
        "failure": 6,
        "rateLimited": 1,
        "slow": 2
      }
    }
  ]
}
```

规则：

- `configurationStatus`: `CONFIGURED / UNCONFIGURED`；秘密值永远不返回。结构非法的模型配置会阻止应用启动，因此不会在已启动实例的 overview 中伪造一个 `INVALID` 运行态。
- `verifiedCapabilities` 只来自明确配置和真实验证记录，不把 adapter 理论能力当已验证。
- breaker 可返回主状态 `CLOSED / OPEN / HALF_OPEN`，以及 Resilience4j 的 `DISABLED`；配置禁用只体现在 deployment `enabled=false`，不能映射成 breaker OPEN。
- 当前状态以 JVM registry 为准，应用重启后会回到 CLOSED。
- `eligibleCandidates` 的口径是生成快照时 `enabled + CONFIGURED` 且对应 capability breaker 允许调用的 deployment-capability 单元数；它不是脱离具体命令后臆造的 RoutePlan 候选数。下钻时只过滤本响应的 deployment/capability 视图。
- `failovers24h` 是最近 24 小时 `failoverCount > 0` 的 logical call 数，不是把一次调用内的切换次数求和；它下钻为 `failoverOccurred=true`。`failedOrInterrupted24h` 是 outcome 为 `FAILED / STREAM_FAILED` 的 logical call 数，下钻为两个重复 outcome。前端不得用已加载的一页 calls 近似过滤。
- `diagnosticsDroppedSinceStart` 与 `diagnosticWriteFailuresSinceStart` 是当前进程内计数，应用重启会清零；两者必须分别返回，不能把读取/组件异常伪装成 0。

页面加载时同时调用现有 `GET /api/v1/health/ready`；本端点不重复代理数据库、Redis、对象存储健康。

## 6. 最近调用

### `GET /api/v1/ai/diagnostics/calls`

查询参数：

| 参数 | 类型 | 说明 |
|---|---|---|
| `capability` | enum | 四种能力 |
| `outcome` | enum，可重复 | `SUCCEEDED / DEGRADED / FAILED / CANCELED / STREAM_FAILED`；重复参数取 OR |
| `tier` | enum | 三个等级 |
| `providerId` | string | 曾发出真实 attempt 的 Provider |
| `failoverOccurred` | boolean | `true` 表示 `failoverCount > 0`，`false` 表示没有故障转移 |
| `requestId` | string | 内部 request ID 或脱敏 Provider request ID 精确匹配 |
| `from` / `to` | timestamp | 默认最近 24 小时；最大跨度 7 天 |
| `page` / `size` | integer | 默认 1 / 20 |

不同筛选字段取 AND，同一 `outcome` 字段内取 OR，例如 `?outcome=FAILED&outcome=STREAM_FAILED` 对应总览的“失败/流中断”下钻。`providerId` 和脱敏 Provider request ID 都通过 `ai_attempt exists` 精确匹配，因此失败的首选 Provider 和 mixed Embedding 也不会丢失。`requestId` 不做模糊搜索，避免大表 `%like%` 和意外枚举标识。

响应：

```json
{
  "items": [
    {
      "id": "bb22a87d-8dbf-4d16-81b9-7a3df0ecb8df",
      "requestId": "req-20260728-001",
      "capability": "CHAT",
      "serviceTier": "BALANCED",
      "outcome": "SUCCEEDED",
      "actualProviderId": "aliyun-model-studio",
      "actualDeploymentId": "aliyun-qwen-plus-cn",
      "actualModelIdentity": "qwen-plus-2025-12-01",
      "lastAttemptTarget": {
        "providerId": "aliyun-model-studio",
        "deploymentId": "aliyun-qwen-plus-cn",
        "modelIdentity": "qwen-plus-2025-12-01"
      },
      "attemptCount": 2,
      "retryCount": 0,
      "failoverCount": 1,
      "failoverOccurred": true,
      "mixedExecution": false,
      "contributingDeploymentCount": 1,
      "committed": false,
      "totalDurationMs": 3842,
      "startedAt": "2026-07-28T17:28:10+08:00",
      "normalizedErrorType": null
    }
  ],
  "page": 1,
  "size": 20,
  "total": 138
}
```

列表不返回候选和完整 attempt，避免每页重复大对象。`lastAttemptTarget` 仅是最后一个真实 outbound attempt 的 target 摘要：它不是成功贡献者、不参与 actual/mixed/contributing 语义，无 attempt 时为 `null`。列表“实际路由”优先显示唯一 `actual*`；mixed execution 显示“多个 deployment”；没有贡献者但有 attempt 时明确显示“最后尝试：…”，不能把它伪装成成功路由。

## 7. 调用详情

### `GET /api/v1/ai/diagnostics/calls/{logicalCallId}`

```json
{
  "call": {
    "id": "bb22a87d-8dbf-4d16-81b9-7a3df0ecb8df",
    "requestId": "req-20260728-001",
    "capability": "STREAM_CHAT",
    "serviceTier": "BALANCED",
    "requiredModelIdentity": null,
    "policyVersion": "phase2-v1",
    "configFingerprint": "7b6a42e2e7c4…",
    "outcome": "STREAM_FAILED",
    "actualProviderId": "aliyun-model-studio",
    "actualDeploymentId": "aliyun-qwen-plus-cn",
    "actualModelIdentity": "qwen-plus-2025-12-01",
    "lastAttemptTarget": {
      "providerId": "aliyun-model-studio",
      "deploymentId": "aliyun-qwen-plus-cn",
      "modelIdentity": "qwen-plus-2025-12-01"
    },
    "providerRequestIdMasked": "req_****9af2",
    "attemptCount": 1,
    "retryCount": 0,
    "failoverCount": 0,
    "failoverOccurred": false,
    "mixedExecution": false,
    "contributingDeploymentCount": 1,
    "committed": true,
    "normalizedErrorType": "STREAM_INTERRUPTED",
    "failurePhase": "UPSTREAM_STREAM",
    "terminationReason": "STREAM_INTERRUPTED_AFTER_COMMIT",
    "firstUpstreamEventMs": 842,
    "firstMeaningfulChunkMs": 1204,
    "downstreamCommitMs": 1216,
    "totalDurationMs": 3842,
    "startedAt": "2026-07-28T17:28:10+08:00",
    "completedAt": "2026-07-28T17:28:14+08:00"
  },
  "candidates": [
    {
      "candidateOrdinal": 0,
      "providerId": "aliyun-model-studio",
      "deploymentId": "aliyun-qwen-plus-cn",
      "modelIdentity": "qwen-plus-2025-12-01",
      "decision": "ELIGIBLE",
      "plannedOrder": 0,
      "rejectionReason": null,
      "breakerStateSnapshot": "CLOSED"
    },
    {
      "candidateOrdinal": 1,
      "providerId": "legacy-ark",
      "deploymentId": "legacy-chat",
      "modelIdentity": "legacy-chat",
      "decision": "FILTERED",
      "plannedOrder": null,
      "rejectionReason": "SERVICE_TIER_MISMATCH",
      "breakerStateSnapshot": "CLOSED"
    }
  ],
  "routeEvents": [
    {
      "sequenceNo": 1,
      "occurredOffsetMs": 2,
      "eventType": "ROUTE_COMPLETED",
      "eligibleCandidateCount": 1
    },
    {
      "sequenceNo": 4,
      "occurredOffsetMs": 3842,
      "eventType": "CALL_TERMINATED",
      "attemptNo": 1,
      "candidateOrder": 0,
      "providerId": "aliyun-model-studio",
      "deploymentId": "aliyun-qwen-plus-cn",
      "reason": "STREAM_INTERRUPTED_AFTER_COMMIT",
      "remainingBudgetMs": 116158
    }
  ],
  "attempts": [
    {
      "attemptNo": 1,
      "workUnitIndex": 0,
      "inputFromInclusive": null,
      "inputToExclusive": null,
      "candidateOrder": 0,
      "attemptKind": "INITIAL",
      "startedSequenceNo": 2,
      "completedSequenceNo": 3,
      "providerId": "aliyun-model-studio",
      "deploymentId": "aliyun-qwen-plus-cn",
      "modelIdentity": "qwen-plus-2025-12-01",
      "breakerStateBefore": "CLOSED",
      "breakerStateAfter": "CLOSED",
      "outcome": "STREAM_FAILED",
      "normalizedErrorType": "STREAM_INTERRUPTED",
      "failurePhase": "UPSTREAM_STREAM",
      "httpStatus": 200,
      "providerRequestIdMasked": "req_****9af2",
      "startedOffsetMs": 18,
      "firstUpstreamEventMs": 842,
      "firstMeaningfulChunkMs": 1204,
      "downstreamCommitMs": 1216,
      "durationMs": 3824,
      "streamEventCount": 18
    }
  ],
  "capabilityDetails": {
    "stream": {
      "incomplete": true
    }
  }
}
```

`routeEvents` 是真实持久化的非 HTTP 调度事件，不是详情 API 根据 attempt 间隙合成的说明文字。前端把 route event 与 attempt 开始/结束、流式里程碑按 offset 合并；同毫秒冲突使用调用级 sequence，attempt 内部里程碑则保持“开始 → 首事件 → 首有效块 → 提交 → 结束”的固定顺序。Attempt DTO 的 `inputFromInclusive/inputToExclusive` 只用于 Embedding；其他能力固定为 `null`。

`capabilityDetails` 是按能力区分的联合结构：

- Chat：finish reason、input/output token；
- Stream：三个时刻、事件数、是否不完整；
- Embedding：身份、输入数、批次数、维度、校验结果、贡献 deployment 和逐批来源；
- Rerank：候选数、top-N、`rerankApplied`、降级原因。

mixed Embedding 的能力详情使用能力专属结构，例如：

```json
{
  "embedding": {
    "modelIdentity": "kb-embedding-v1",
    "inputCount": 96,
    "batchCount": 2,
    "dimensions": 1024,
    "mixedExecution": true,
    "contributingDeployments": [
      {
        "providerId": "aliyun-model-studio",
        "deploymentId": "embedding-cn-a",
        "modelIdentity": "kb-embedding-v1"
      },
      {
        "providerId": "backup-cn",
        "deploymentId": "embedding-cn-b",
        "modelIdentity": "kb-embedding-v1"
      }
    ],
    "batches": [
      {
        "workUnitIndex": 0,
        "inputFromInclusive": 0,
        "inputToExclusive": 64,
        "actualExecution": {
          "providerId": "aliyun-model-studio",
          "deploymentId": "embedding-cn-a",
          "modelIdentity": "kb-embedding-v1"
        },
        "attemptCount": 1,
        "retryCount": 0,
        "failoverCount": 0
      },
      {
        "workUnitIndex": 1,
        "inputFromInclusive": 64,
        "inputToExclusive": 96,
        "actualExecution": {
          "providerId": "backup-cn",
          "deploymentId": "embedding-cn-b",
          "modelIdentity": "kb-embedding-v1"
        },
        "attemptCount": 2,
        "retryCount": 0,
        "failoverCount": 1
      }
    ],
    "validation": {
      "countAndOrderValid": true,
      "dimensionsValid": true,
      "finiteValues": true
    }
  }
}
```

Embedding 的 `batches` 按持久化 attempt 中的 `workUnitIndex + inputFromInclusive + inputToExclusive` 分组，并从每组最终成功的贡献 attempt 组装；不能根据当前 catalog 的 batch limit 回推历史范围。失败 attempt 也保留当时范围并出现在 `attempts`，但不成为 batch contributor。

对应 call 的 `contributingDeploymentCount=2`、`mixedExecution=true`，调用级 `actualProviderId/actualDeploymentId/actualModelIdentity/providerRequestIdMasked` 全部为 `null`；不能任选一个贡献者冒充唯一实际路由。

它永远不包含正文。

## 8. 前端消费约定

- `/diagnostics` 首次进入并行加载 readiness、overview 和 calls。
- 页面可见时每 30 秒刷新 overview；标签页隐藏时暂停。调用列表不自动跳动，只在首次进入、筛选变化或用户点“刷新”时加载。
- `view`、筛选和时间范围同步到 URL query；当前选中 call ID 留在页面状态，不承诺可分享深链。
- 刷新失败保留上次成功数据并标记“可能已过期”。
- 详情关闭后保留列表滚动和筛选。
- 不提供下载完整 JSON；只允许复制内部 request ID 和已脱敏 Provider request ID。

## 9. 兼容与版本

- HTTP API 使用 `/api/v1`，新增字段向后兼容；删除/改名需要新版本。
- `diagnosticSchemaVersion` 用于解释历史行，不等同于 HTTP API 版本。
- Phase 1 `EmbeddingClient` 在一个原子迁移工单中改成命令式深接口；该提交同时迁移所有实现和调用点，不存在新旧方法长期并行期。
- 未来 Provider 新增事件类型时，适配器应忽略未知的非业务事件并留诊断；不得把未知字段视为整个响应失败。
