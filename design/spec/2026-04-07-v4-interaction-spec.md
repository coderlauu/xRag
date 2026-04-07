# v4 Interaction Spec

**日期：** 2026-04-07  
**版本：** `v4 / Phase 2A`  
**状态：** draft  
**对应原型：** [prototype/v4](/Users/coderlauu/xRag/prototype/v4/index.html)

---

## 1. 文档目的

本说明定义 `Phase 2A` 的页面结构、交互规则、状态流转和异常反馈，用于：

- 衔接 `Phase 2A PRD` 与后续技术方案
- 明确 `AI 问答 / 混合检索 / 引用 / 拒答 / 评估` 的页面行为
- 让前端、后端、检索链路、模型层与运维共享同一份交互事实源

---

## 2. 页面总览

### 页面列表

- `index.html`：问答工作台
- `search.html`：检索实验台
- `detail.html`：证据与引用详情页
- `ops.html`：评估与运维板

### 全局原则

- 页面重点从“文档如何被处理”转向“答案为什么可信、为什么拒答、为什么选这些证据”
- 所有回答都必须显式展示范围、引用和新鲜度
- 无证据时允许拒答，不允许把“回答失败”伪装成“答案很弱”
- 搜索页、问答页、详情页必须共享同一套检索与引用事实源

---

## 3. 核心对象与状态

### 文档对象

关键字段：

- `id`
- `title`
- `source_type`
- `parse_status`
- `index_status`
- `index_version`
- `content_preview`
- `citation_ready`
- `diagnosis_code`
- `freshness_summary`

### 问答会话对象

关键字段：

- `session_id`
- `question`
- `scope_mode`
- `scope_summary`
- `answer_status`
- `answer_summary`
- `refusal_reason`
- `retrieval_mode`
- `citations`
- `latency_ms`
- `token_cost`

### 处理状态

#### 索引状态

- `not_indexed`
- `queued`
- `chunking`
- `embedding`
- `ready`
- `failed`
- `stale`

#### 问答状态

- `idle`
- `retrieving`
- `synthesizing`
- `answered`
- `needs_scope`
- `refused`
- `failed`

### 失败诊断分类

- `index_embedding_failed`
- `index_chunk_too_large`
- `retrieval_no_hits`
- `answer_insufficient_evidence`
- `citation_missing`
- `provider_timeout`
- `rerank_budget_exceeded`

---

## 4. 问答工作台

### 页面目标

让用户把“问问题”变成产品一级能力，并明确看到：

- 当前问题问的是什么
- 问题限定在什么范围
- 系统给出的答案是否可信
- 引用了哪些文档和片段
- 如果暂时答不了，缺的是证据还是范围

### 主要模块

1. 版本 Hero
2. 问题输入区
3. 范围控制条
4. 当前答案卡片
5. 引用证据列表
6. 索引 freshness 信号
7. 最近问题

### 核心交互

#### 4.1 问题输入

输入区至少支持：

- 自由问题输入
- 选择是否限定到当前搜索结果
- 选择是否限定到标签 / 来源类型 / 时间范围 / 单篇文档

提交后，界面必须立即展示：

- 当前 scope
- 当前 answer status
- 正在检索还是正在生成

#### 4.2 范围控制

用户可以切换：

- 全库
- 当前搜索结果
- 指定标签
- 指定来源类型
- 指定时间范围
- 单篇文档

切换 scope 后必须同步更新：

- 问题卡片中的 scope 标签
- 可用证据数
- 答案或拒答说明

#### 4.3 答案卡片

答案卡片至少展示：

- 问题
- 一段可直接消费的回答摘要
- `retrieval mode`
- 引用数量
- 延迟 / token 成本摘要
- 结果是 `answered / needs_scope / refused / failed`

#### 4.4 拒答与补救

当证据不足时，不展示模糊答案，而要展示：

- 当前为什么不能回答
- 推荐缩小范围还是补充资料
- 推荐打开搜索页看哪些文档被召回

---

## 5. 检索实验台

### 页面目标

让用户和产品/工程都看清楚：

- 当前问题是怎么召回这些文档的
- 关键词、语义与 rerank 各自贡献了什么
- 为什么有些文档没进答案

### 主要模块

1. 问题输入
2. 检索模式切换
3. 索引状态筛选
4. 来源 / 标签 / 诊断筛选
5. 召回结果列表
6. 检索解释与建议

### 核心交互

#### 5.1 检索模式

至少支持：

- `keyword`
- `semantic`
- `hybrid`

结果卡片应显示：

- lexical signal
- semantic signal
- rerank reason
- 是否进入最终答案引用

#### 5.2 未进入答案的结果

被召回但未被引用的结果仍应显示：

- 为什么被召回
- 为什么没进入答案
- 是否需要用户缩小范围或重建索引

#### 5.3 URL State

问题、检索模式、来源类型、索引状态、标签与诊断类型都应进入 URL。

---

## 6. 证据与引用详情页

### 页面目标

为单条文档或单次引用提供可核查证据，展示：

- 文档原始信息
- 可引用片段
- 引用定位
- 检索轨迹
- 索引 freshness
- 重建动作

### 核心交互

#### 6.1 引用视图

至少展示：

- 被引用片段正文
- 片段来源位置
- 命中关键词或语义说明
- 被哪次答案引用

#### 6.2 索引状态视图

需要明确告诉用户：

- 文档是否已进入问答索引
- 当前索引版本
- 最后一次索引时间
- 若索引失败，应如何回补

#### 6.3 证据颗粒度

`Phase 2A` 的详情页必须能分清：

- 原始文档层
- chunk 层
- retrieval hit 层
- citation 层

---

## 7. 评估与运维板

### 页面目标

让产品、工程和运维快速判断问题位于：

- 文档索引
- retrieval
- rerank
- answer generation
- provider latency / cost
- 评估集质量

### 主要模块

1. 线上健康与 feature flag
2. 评估指标总览
3. 最近失败会话
4. 评估集覆盖情况
5. 回滚与降级策略

### 核心交互

#### 7.1 评估指标

至少展示：

- retrieval recall
- citation coverage
- groundedness
- refusal precision
- p95 latency
- average token cost

#### 7.2 线上失败会话

失败会话应按原因聚合：

- 没召回
- 有召回但无足够证据
- 引用缺失
- provider 超时

#### 7.3 降级动作

至少支持展示：

- 关闭生成式答案，只保留 semantic retrieval
- 关闭 semantic retrieval，只回到 keyword baseline
- 保留索引更新，但暂停新问答流量

---

## 8. 容易忽略的交互细节

- scope 变化后若答案失效，页面必须主动重置为待生成，而不是沿用旧答案
- “引用数量多”不等于“答案可信”，需要强调关键证据而不是堆数量
- 拒答文案必须清楚区分“没有证据”和“没有权限 / 不在范围”
- 索引 stale 的文档在搜索页、问答页、详情页都要统一提示
