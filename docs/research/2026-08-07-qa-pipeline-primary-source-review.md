# Phase 3 AI 知识问答核心链路一手资料复核（最小范围）

- 日期：2026-08-07
- 范围：**刻意压到两项**——(1) 多条 `SystemMessage` 在消息数组中间是否被百炼接受；(2) `qwen-plus` 的上下文窗口
- 对应检查点：[Phase 3 Step A 学习检查点](../../learning/ragent-column/PHASE-3-STEP-A-CHECKPOINT.md) §2 `2026-08-07` 条目
- 研究性质：Step A 学习支撑；本文不冻结 Phase 3 的 API、Schema 或 Prompt 结构

> **范围是被明确裁决过的。** 项目所有者于 `2026-08-07` 决定只查上述两项，因为它们直接影响 Step C 的
> Prompt 结构与数据模型。另两项已知未查证事项——**百炼 compatible-mode 的 prompt caching 支持形态**、
> **Lost in the Middle（Liu et al., 2023）的结论边界**——**明确不查，且在 Phase 3 全程不得作为设计依据引用**。

## 结论摘要

1. **官方文档不解决"多条 system message"这个问题，因此在实测之前不能采用依赖它的 Prompt 结构。**
   文档只说 system message"一般放在 `messages` 数组的第一位"——**用的是"一般"**，既非强制，也未对
   多条 system message 或消息排列顺序给出任何允许/禁止的表述。**沉默不等于支持。**
2. **`qwen-plus` 的上下文是 1M，因此上下文预算不是 Phase 3 的近期瓶颈**——但 `ModelDeployment`
   仍应补上窗口字段，理由不是"现在不够用"，而是路由要按上下文规模过滤候选、且换模型时这个数字会变。
3. **顺带确认了一件支撑 D-03 的事实**：官方文档明确 `qwen-plus` 及其快照版本支持 Function Calling，
   与 catalog 里已声明的 `features: [TOOL_CALLING]` 一致。**原生 function calling 路线（路线 B）有一手依据。**
4. **一个未预期的发现**：`qwen-plus` 在官方文档中已被归入**「旧版 Qwen」**分区，当前主力是
   `qwen3.7-plus` / `qwen3.8-max`。不影响 D-05（本期继续用它），但需登记。

## 1. 多条 `SystemMessage` 与消息排列顺序

### 1.1 文档实际表述

查 [OpenAI 兼容 - Chat](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions)，就三条：

| 问题 | 文档表述 |
|---|---|
| system message 是否必须在第一位 | 只说「**一般**放在 `messages` 数组的第一位」——惯例措辞，**无强制要求** |
| 是否允许多条 system message | **无任何表述**，既不允许也不禁止 |
| system/user/assistant 排列顺序 | **无强制约束**；文档只给出各消息类型的结构定义 |

### 1.2 对 Phase 3 的结论

第 14 篇的四段式骨架把**对话摘要作为第二条 `SystemMessage` 插在数组中间**（`system` 规则 →
`system` 摘要 → 历史轮次 → 证据+问题）。这个结构**依赖一个文档没有承诺的行为**。

- **不能据此认为它不可行**——文档没有禁止，OpenAI 兼容层通常也接受多条 system。
- **但更不能据此认为它可行**——这正是 [ADR 0001](../adr/0001-build-xrag-independently.md) 要求的
  "引用外部结论必须注明所查版本"的反面：**这里根本不存在可引用的结论。**

**因此 Step C 的 Prompt 结构有两条路，二选一必须显式记录：**

| 方案 | 做法 | 前提 |
|---|---|---|
| **A（照搬原文）** | 摘要作为独立的第二条 `SystemMessage` | **必须先实测通过**——用 `RealChatApiTests` 那套 `@EnabledIfEnvironmentVariable` 守门加一个用例即可 |
| **B（不依赖未验证行为）** | 摘要并入**首条** `SystemMessage`，或并入证据体所在的那条 `UserMessage` | 无前提，立即可用 |

**建议：Step C 先按 B 设计，把 A 作为实测通过后的优化项。** 理由是 B 不损失任何已论证的收益——
第 14 篇为"证据放 user"给的三条理由（距离问题近、system 保持精简、贴合 SFT 分布）**没有一条依赖
摘要单独成条**；而 A 一旦在 Step D 才发现不被接受，改的是整个消息组装层。

## 2. `qwen-plus` 的上下文窗口

### 2.1 文档实际数值

查 [文本生成](https://help.aliyun.com/zh/model-studio/text-generation-model/)，`qwen-plus` 及其快照版本：

| 项 | 值 |
|---|---|
| 上下文（Context） | **1M** |
| 思考模式 | 支持 |
| **Function Calling** | **支持** |
| 内置工具 | 支持 |
| 结构化输出 | 支持 |

**未取到的**：最大输入 token、最大输出 token。该页未列出，且注明"如需查看模型详细参数（上下文窗口、
计费等），请前往模型广场"。[模型价格页](https://help.aliyun.com/zh/model-studio/model-pricing)
正文在抓取时被截断，未能取到单价。**这两项属于已知缺口，Step C 若需要精确的输出上限，必须重查。**

### 2.2 对 Phase 3 的结论

- **上下文预算不是近期瓶颈。** 1M 之下，第 3 篇的水位线压缩与第 14 篇的证据体（5 条 chunk + 一份
  JSON）都不紧张。**这修正了第 14 篇笔记里"history 压到线下加上证据仍可能超窗"的紧迫性判断**——
  该风险在 1M 窗口下近乎不存在，但**在换成小窗口模型时会立刻回来**。
- **`ModelDeployment` 仍应补窗口字段**，理由与"现在够不够用"无关：
  1. Phase 2 的一手复核已经立过"路由必须先剔除不支持所需上下文规模的候选"这条原则，而现在
     `ModelDeployment` 里**根本没有这个字段可供过滤**；
  2. 该数值随模型变，硬编码或省略都会在换模型时静默出错。
- **计费口径未确认**：`qwen-plus` 的资源包对"单次请求超过 128K token 的部分不予抵扣"这类规则在搜索
  结果中出现过但**未在一手页面核实**，本文不采信。若 Phase 3 要做成本估算，需专门重查。

### 2.3 登记：`qwen-plus` 已属「旧版 Qwen」

catalog 里配的是 `qwen-plus-2025-12-01`（`upstream-model` 与 `model-identity` 同值）。官方文档把
`qwen-plus` 及其快照版本列在**「旧版 Qwen」**分区下，当前主力型号是 `qwen3.7-plus` 与 `qwen3.8-max`。

- **不影响 D-05**：本期继续用 `qwen-plus`，它仍在售且能力齐全。
- **但要登记两点**：① 后续若换新版，`model-identity` 会变——按 Phase 2 的既有纪律，
  **模型身份是知识库不变量，Chat 侧虽无此约束，但诊断表里的历史记录会出现身份断层**；
  ② Phase 4 评测若要做模型对比，新旧两代同时在售是有利条件。

## 3. 一手来源清单

| 来源 | 用途 | 查证日期 |
|---|---|---|
| [OpenAI 兼容 - Chat](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions) | system message 位置与数量的表述 | 2026-08-07 |
| [文本生成（模型能力表）](https://help.aliyun.com/zh/model-studio/text-generation-model/) | `qwen-plus` 上下文 1M、Function Calling 支持、「旧版 Qwen」归类 | 2026-08-07 |
| [模型列表](https://help.aliyun.com/zh/model-studio/models) | 当前在售型号（`qwen3.7-plus` / `qwen3.8-max`） | 2026-08-07 |
| [模型价格](https://help.aliyun.com/zh/model-studio/model-pricing) | **抓取截断，未取到数据** | 2026-08-07 |

## 4. 遗留缺口（不阻塞 Step A 关闭）

1. **多条 `SystemMessage` 的实测**——需要一次真实调用；建议并入 Provider 准入的既有测试类，不新起一套。
2. **`qwen-plus` 最大输入/输出 token**——需查模型广场页面，Step C 定数据模型时补。
3. **单价与资源包抵扣规则**——若要做成本估算再查。
