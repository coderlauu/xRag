# 《把 RAGAS 跑起来》

> 原文链接：https://t.zsxq.com/25Iqw
> 所属分组：06-evaluation RAG 评测体系

## 一句话概括

跑通 RAGAS 不只是安装 Python 包，而是固定依赖版本、judge 与 embedding 身份，把录制记录转换为框架要求的样本，并在超时、限流和部分失败时保留可审计结果。

## 核心内容提炼

- 课程用独立 Python 环境安装 RAGAS、模型适配与数据集依赖；Python 3.11 是其验证过的环境，不是所有版本的永久硬规则。
- RAGAS 指标可能同时依赖 judge LLM 和 embedding，二者都要显式配置。
- 输入字段从评测记录映射为 user input、response、retrieved contexts 和 reference；缺少所需字段的样本应标为不可评，而非用空字符串冒充。
- 课程按当前 RAGAS 版本构造 metrics collection、调用 evaluate 并读取逐样本结果，再转换为项目统一的 `MetricResult`。
- 外部模型调用需要超时、重试、并发上限和错误隔离；任一语义指标失败不应抹掉已计算的确定性指标。

## 关键概念/术语

- **适配层**：把项目统一样本/结果模型与第三方框架 API 互相转换的边界。
- **可评资格（eligibility）**：某条样本具备某项指标所需全部输入。
- **部分成功**：一部分指标或样本成功，失败部分带原因保存，而非整批丢弃。
- **依赖锁定**：固定 Python、RAGAS、模型 SDK 等版本，保证同一评测定义可重建。

## 与 xrag 项目的关系

- 若采用 Python sidecar/项目，应只消费稳定、脱敏的评测记录契约，不直接查询 xrag 生产数据库内部表。
- 评测 Provider 凭据、费用和网络调用要与 Java 默认 CI 隔离；缺凭据时明确 skip，而不是悄悄换成本地假分数。
- xrag 应先定义框架无关的 `EvaluationSample` / `MetricObservation` 语义，再决定 RAGAS 适配器，避免数据库直接使用第三方字段名。
- 同一次评测必须记录依赖锁文件、环境摘要和实际模型响应元数据，才能重跑或解释差异。

## 值得注意的设计取舍/坑

- 课程中具体 factory、metric collection 和结果字段是版本相关 API；Step D 前需用小型原型验证当前版本。
- OpenAI-compatible 不保证 JSON mode、token 计费、重试语义或 embedding 维度完全一致。
- 大超时与自动重试会扩大费用和尾延迟；每个 run 应有样本数、调用数、Token/金额和墙钟时间预算。
