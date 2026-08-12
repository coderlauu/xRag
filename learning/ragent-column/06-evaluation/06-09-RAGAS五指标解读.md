# 《RAGAS 五个指标全解读》

> 原文链接：https://t.zsxq.com/PaWec
> 所属分组：06-evaluation RAG 评测体系

## 一句话概括

五个指标必须按“比较谁、依赖什么、低分先查哪一层”来读：忠实不等于正确，切题不等于事实正确，文档命中也不等于上下文覆盖。

## 核心内容提炼

- **Faithfulness** 从 response 拆陈述，判断其是否受 contexts 支持；它发现“多说了什么”，但不能证明 contexts 本身正确。
- **Answer relevancy** 判断回答是否聚焦原问题；课程描述为从回答反向生成问题再做语义比较。它不验证事实真伪。
- **Context precision** 判断各个 context 对正确回答是否有用，并考虑有用内容的排序；相关项靠前分数更高。
- **Context recall** 从 reference 拆信息点，判断 contexts 覆盖多少；它发现“检索漏了什么”。
- **Answer correctness** 比较 response 与 reference 的事实/语义一致性，是端到端报警信号，定位仍需配合另外四项。
- 配对读法：`context_recall × faithfulness` 区分上游漏检与下游乱编；`context_precision × context_recall` 判断检索过宽/过窄；`relevancy × correctness` 区分跑题与答错。

## 关键概念/术语

- **Claim / statement**：从回答或参考答案拆出的可独立判断信息单元。
- **Supported / covered**：上下文能支持回答陈述，或覆盖参考答案信息点。
- **Average Precision**：兼顾相关项数量与排序位置的精度聚合方式。
- **指标对偶**：从实际回答查无依据内容，与从参考答案查遗漏内容的相反观察方向。

## 与 xrag 项目的关系

- contexts 必须是模型本次实际看到的有序证据，而不是另一次检索或所有粗召回结果，否则五项指标的输入边界不成立。
- SYSTEM 直答、工具结果和澄清路径需要各自定义 context/reference 语义；不能默认所有 `response` 都用知识库 chunks 评分。
- reference 可能有多个合法答案。对于推荐、解释或开放问题，应考虑 rubric、多参考答案或人工判定，而不是把单一措辞当唯一事实。
- 每项结果要附资格状态与失败原因；空回答、空 contexts、空 reference 不是数值 0 的同一种失败。

## 值得注意的设计取舍/坑

- 课程给出的内部公式与权重是特定版本实现描述，框架升级可能变化；文档应链接并锁定实际版本定义。
- Judge 的 claim 粒度会直接改变分母，跨 Prompt/模型比较前必须验证拆解一致性。
- 高 faithfulness + 低 correctness 可能是知识库过时，也可能是 reference 错；自动诊断只能给排查顺序，不能直接下根因结论。
