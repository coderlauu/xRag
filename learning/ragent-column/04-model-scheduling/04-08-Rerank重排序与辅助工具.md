# 《Rerank重排序与辅助工具》

> 原文链接：https://t.zsxq.com/xKwgo
> 所属分组：04-model-scheduling 大模型调度引擎

## 一句话概括

Rerank 是召回后的可选质量增强：客户端必须用稳定候选身份映射 Provider 返回的 `index + score`，失败时可以显式降级为保持原顺序；Token 估算和响应清洗则应保持小而独立，不混进调度核心。

## 核心内容提炼

- 典型两阶段检索是：Embedding 双编码器快速召回较多候选，再由交叉编码器对 query 与候选逐对评分，选出更小的 Top-N。
- 原文用 `RetrievedChunk(id, text, score)` 保留候选身份和原始分数，`RerankService` 接收 query、候选与 top-N，Provider `RerankClient` 再增加 `ModelTarget`。
- 百炼客户端先按 chunk ID 去重并保留第一次出现的候选；当 `topN <= 0` 或候选数不超过 top-N 时直接返回，不发没有价值的远程请求。
- 请求包含模型、query、documents 和 top-N；响应结果携带候选 `index` 与相关性分数。客户端按 index 映射回原候选，并构造带新分数的结果。
- 对越界或缺失结果，原文跳过非法项；如果有效结果不足 top-N，再从原候选顺序回填，保留原始分数。
- `NoopRerankClient` 是空对象模式：返回原顺序的前 top-N，作为低优先级最终候选，使“未配置 Rerank”仍能继续问答链路。
- `RoutingRerankService` 复用模型选择、通用执行器和健康存储，展示同步 Chat、Embedding、Rerank 共用调度骨架的最终形态。
- 辅助工具包括：
  - 启发式 Token 估算：ASCII 约 4 字符/token，CJK 约 1 字/token，其他字符约 2 字/token；
  - 响应清洗：移除模型在 JSON 等结构化输出外多加的 Markdown 代码围栏。

## 关键概念/术语

- **双编码器召回**：分别向量化 query 和文档，适合大规模快速筛选。
- **交叉编码器重排**：把 query 与候选共同输入模型，精度通常更高但成本更大。
- **候选身份映射**：Provider 返回索引后映射回内部稳定 ID，避免正文重复或排序变化导致错配。
- **Noop 降级**：不调用模型、保留原顺序的显式低质量路径。
- **启发式 Token 估算**：不加载具体模型词表的近似计数，只适合软预算。

## 与 xrag 项目的关系

- Phase 3 的检索链路将消费 Rerank，因此 Phase 2 先提供稳定端口、Provider 适配和降级语义，不在本阶段实现完整知识问答。
- xrag 的 Rerank 入参应包含稳定 chunk ID 和可发送文本，出参引用内部 ID，同时保留 Provider 原始 index、分数和请求 ID供诊断。
- 第一版可支持“无 Rerank 候选 → 显式 Noop”，但诊断与最终响应元数据必须标记 `rerankApplied=false`，不能让运维误以为重排成功。
- `topN` 只限制返回数量，不等于 Provider 没有对文档长度、候选数和 token 做截断；这些限制应登记为能力元数据并在调用前处理。
- Token 启发式计数适合 Phase 3 的对话预算预估，不适合精确计费、硬上下文限制或跨模型统一统计。需要精确值时优先使用 Provider usage 或对应 tokenizer。
- `LLMResponseCleaner` 可以作为纯工具保留，但结构化输出更应优先使用 Provider 原生 JSON schema/structured output 能力；清洗代码围栏只是兼容兜底。

## 值得注意的设计取舍/坑

- **非法或重复 index 不应静默跳过。** 课程的“跳过并回填”有可用性价值，但会掩盖 Provider 协议错误；xrag 应校验越界、重复和缺失，并由产品契约决定严格失败还是带诊断降级。
- **Rerank 分数不能跨模型直接比较。** 分数只保证在同一模型、同一次调用内用于排序。
- **Noop 是降级结果，不是健康成功。** 不应复位真实 Rerank Provider 的熔断状态，也不能计入模型质量指标。
- **`topN <= 0` 的语义必须先定义。** 课程示例会直接返回全部去重候选；xrag 更适合在端口层拒绝非法值或明确把它定义为“不截断”，不能让调用方猜测。
- **按 ID 去重需要定义重复候选的元数据取舍。** “保留第一次”简单稳定，但若重复项携带不同来源或原始分数，需要在 Phase 3 明确合并规则。
- **启发式 Token 误差不能用于硬限制。** 模型的 tokenizer 不同，中文也未必固定一字一 token；硬上限必须留安全余量或使用精确 tokenizer。
