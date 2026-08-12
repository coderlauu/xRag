# 《RAGAS 是什么与选型》

> 原文链接：https://t.zsxq.com/nUK3K
> 所属分组：06-evaluation RAG 评测体系

## 一句话概括

RAGAS 的价值是把难以用集合运算判断的语义质量拆成若干可诊断信号，但框架与指标必须服从评测问题、数据条件和成本边界，而不是因为流行就全量接入。

## 核心内容提炼

- 课程选择五个语义指标：生成层的 faithfulness、answer relevancy、answer correctness；检索层的 context precision、context recall。
- 选择标准是跑得起、解释得清、低分能指向行动，而不是追求指标数量。
- 自建指标与 RAGAS 互补：前者确定、快、适合频繁回归；后者可读语义，但依赖 judge/embedding、耗时与费用更高。
- 指标组合比单项分数更有诊断价值，例如 context recall 低而 faithfulness 高通常先查检索；precision 低而 recall 高则先查噪声与排序。
- 课程不选实体召回、噪声敏感性、多轮、工具和自定义 rubric，是因为当前评估集小且场景单轮，而非这些指标普遍无效。

## 关键概念/术语

- **LLM-as-judge**：用模型按固定协议判断另一个系统输出。
- **Faithfulness**：回答陈述是否能被给定上下文支持。
- **Answer relevance/correctness**：回答是否切题、是否与参考答案一致；两者不是同义词。
- **Context precision/recall**：上下文的有效排序与信息覆盖，区别于文档 ID 级指标。

## 与 xrag 项目的关系

- RAGAS 只能作为候选适配器；Step B/C 要先定 xrag 的评测问题、样本结构与证据边界，再验证当前版本 API 和中文效果。
- xrag 有 SYSTEM、工具、澄清和多子问题路径，课程五指标只覆盖其中 RAG 问答子集，不能代表完整链路质量。
- judge 配置必须当作评测版本的一部分：实际模型、供应商、Prompt、temperature/seed（若支持）、embedding、RAGAS 版本和失败重试策略都要记录。
- 自动指标必须保留 per-sample 理由或中间判定，并接受人工复核；只存一个 overall 浮点数不可审计。

## 值得注意的设计取舍/坑

- 课程对 RAGAS 具体算法和类名绑定 `0.4.3`，属于版本化描述；实现前必须查当前官方文档，不能按课程代码盲接。
- 语义指标名称稳定不代表定义稳定，框架版本或 Prompt 变化后分数不一定可横向比较。
- 固定阈值不能脱离风险域、reference 质量、judge 与人工一致率解释。
