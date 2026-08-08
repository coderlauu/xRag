# ADR 0006：将 Chat 采样参数绑定到服务等级

- 状态：已接受（2026-08-07）
- 日期：2026-08-07
- 相关：[ADR 0003](0003-separate-model-capability-ports.md)、
  [Phase 3 Step A 检查点](../../learning/ragent-column/PHASE-3-STEP-A-CHECKPOINT.md)、
  [Phase 3 执行计划](../exec-plans/active/2026-08-07-qa-pipeline-phase-plan.md)

## 背景

`ChatCommand` 只让业务调用方声明消息、工具、`ServiceTier` 与调用控制；`CallOptions` 只承载请求标识、
超时、取消和数据区域。同步与流式 Chat 在选出 deployment 后，当前只把消息和工具传给 Provider，无法表达
`temperature` 或 `topP`。

Phase 3 的摘要、查询改写、意图判断和最终回答需要不同的采样取舍。若把供应商参数塞进
`CallOptions`，业务代码会知道 OpenAI/DashScope 的字段名、取值域和方言；若依赖 Provider 默认值，同一
服务等级的行为会在供应商或模型版本变化后静默漂移。Phase 3 Step A 的 D-06 已裁决：采样参数绑定
`ServiceTier` 并由 catalog 固定；D-07 同时裁决本期不做 `thinking`。

## 决策

### 1. 业务端口只选择服务等级

- `ChatCommand.tier` 继续是业务调用方选择采样策略的唯一入口。
- `CallOptions` 不增加 `temperature`、`topP`、`thinking` 或任意供应商透传 Map。
- `ChatClient` / `StreamingChatClient` 的公开方法形状不变。此次变更发生在 `com.app.ai` 的模型目录与
  Provider SPI 一侧，不把参数旋钮暴露给问答模块。

### 2. 每个 Chat deployment 按 tier 声明不可变采样配置

Chat 或流式 Chat deployment 在 catalog 中增加 `chat.sampling-by-tier`：

```yaml
deployments:
  aliyun-qwen-plus-cn:
    capabilities: [CHAT, STREAM_CHAT]
    tiers: [BALANCED]
    chat:
      sampling-by-tier:
        BALANCED:
          temperature: 0.1
          top-p: 0.3
```

目录解析后形成不可变的 `ChatProfile` 与 `ChatSamplingParameters`。路由仍先按调用方请求的 tier 选择
deployment；每次 attempt 再从该 deployment 解析同一 tier 的采样配置。因此故障转移到另一个
deployment 时使用目标 deployment 自己的配置，不复用首选模型的供应商参数。

同步与流式调用必须走同一解析函数，并把已解析参数作为 `ProviderChatRequest` 的必填部分传给适配器。
OpenAI-compatible 适配器分别映射为 `temperature` 与 `top_p`；不得静默省略已配置参数。

### 3. 启动时失败，不在调用时猜默认值

- 只有声明 `CHAT` 或 `STREAM_CHAT` 的 deployment 可以配置 `chat`。
- Chat deployment 的 `sampling-by-tier` 键必须与其 `tiers` 完全一致；缺项、额外等级或空 Map 均导致
  启动失败。
- `temperature` 与 `top-p` 都是必填有限数：`temperature` 取 `[0, 2]`，`top-p` 取 `(0, 1]`。
- adapter 必须在应用装配阶段确认能表达目录中的配置；不支持时启动失败。不得在真实请求中丢字段后继续，
  也不得临时回退到供应商默认值。
- `thinking` 不进入配置类型、Provider 请求或事件协议；需要它时另开产品决策和端口 ADR。

### 4. 配置指纹和诊断是唯一追溯面

采样配置按 deployment ID、tier 和固定字段顺序进入 catalog fingerprint。现有完成态诊断已经保存
`serviceTier`、`policyVersion` 与 `configFingerprint`，三者足以还原当次调用采用的目录版本；不在每条
调用记录中重复保存温度或 `top-p`，避免产生第二份可漂移事实。

采样值不属于密钥，可以出现在受版本控制的 catalog 与技术文档中；仍不得把请求正文、模型输出或供应商
错误体加入诊断。

## 后果

- 摘要、改写、意图与答案生成只表达服务等级，不依赖具体 Provider。
- 新 Chat adapter 必须实现显式采样映射和装配期能力校验；这是防止静默行为漂移的有意成本。
- 同步、流式、重试与故障转移共享同一参数解析语义，适配器测试可以直接断言真实请求体。
- catalog 迁移必须一次补齐所有 Chat deployment 的采样配置，否则应用拒绝启动；不会保留隐式兼容默认值。
- `ServiceTier` 仍表示成本、延迟和质量的路由意图，不等于采样参数本身。Phase 3A Step C 仍要决定
  `qwen-plus` 只开放 `BALANCED`，还是让同一固定模型 deployment 承担多个可路由 tier；本 ADR 不替该
  数据模型决策预留不可达等级。项目所有者已于 2026-08-07 随 3A Step C 决策包确认：3A 只开放
  `BALANCED`，采样基线为 `temperature=0.1`、`top-p=0.3`；3C 如需新等级，必须在当段重新形成真实
  可达的目录配置，不能现在预建。

## 被否决方案

1. **把参数放进 `CallOptions`**：实现直接，但让每个业务调用点接触供应商旋钮，并使超时/取消等调用控制
   与模型生成策略混杂。
2. **让 Provider 使用默认值**：无需改端口，但目录 fingerprint 无法解释行为，供应商默认变更会形成
   无版本记录的线上漂移。
3. **接受任意 `Map<String, Object>` 透传**：能覆盖供应商方言，却失去类型、范围和 adapter 能力校验，
   也给业务层绕过端口边界留下入口。
