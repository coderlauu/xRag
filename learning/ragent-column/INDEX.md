# 学习索引：知识星球《Ragent AI》专栏

来源：知识星球专栏 [Ragent AI](https://wx.zsxq.com/columns/51121244585524?column_id=281884884411)（作者"马丁"），配套开源仓库 [nageoffer/ragent](https://github.com/nageoffer/ragent)。

原作者按"教学讲解顺序"分了 9 组，这里按"系统模块归属"重新组织成 8 组（含 1 个前言组），方便每组学习内容和 `xrag` 项目里对应要设计/实现的系统模块直接挂钩。具体缘由见 [docs/exec-plans/active/2026-07-24-ragent-column-learning-and-system-build.md](../../docs/exec-plans/active/2026-07-24-ragent-column-learning-and-system-build.md)。

全专栏共 92 个链接（1 个加群链接 + 91 篇正文），下表逐条登记原文标题、原文短链接、新分组与本地整理稿路径。**状态**：`done` = 已整理，`pending` = 待处理。

截至 2026-07-25，全部正文完成 **51/91**；Phase 0（00~02组）40/40 全部完成；Phase 1（03-knowledge-base组）11/11 全部完成。

- `done`：`00-01`～`00-05`、`01-01`～`01-25`、`02-01`～`02-10`、`03-01`～`03-11`
- `pending`：`04-model-scheduling`（8篇）、`05-qa-pipeline`（18篇）、`06-evaluation`（11篇）、`07-interview`（3篇+持续更新）

Phase 0 的覆盖率、技术结论、环境验证与进入下一阶段前的待决策事项见 [Phase 0 对齐检查点](PHASE-0-CHECKPOINT.md)。

## 处理进度总览

| 分组 | 篇数 | 状态 | 对应系统模块 |
|---|---|---|---|
| 00-overview 项目概览与学习指南 | 5 | done（5/5） | 无，背景/元信息 |
| 01-rag-fundamentals RAG与大模型基础概念 | 25 | done（25/25） | 全系统知识底座 |
| 02-bootstrap-and-deployment 本地开发与模型部署环境 | 10 | done（10/10） | 项目 Bootstrap/基础设施 |
| 03-knowledge-base AI知识库建设 | 11 | done（11/11） | 知识库/文档管理模块 |
| 04-model-scheduling 大模型调度引擎 | 8 | pending（Phase 2） | AI基础设施层/模型路由网关模块 |
| 05-qa-pipeline AI知识问答核心链路 | 18 | pending（Phase 3） | 检索增强问答（核心业务）模块 |
| 06-evaluation RAG评测体系 | 11 | pending（Phase 4） | 评测/质量保障模块 |
| 07-interview 面试高频考点 | 3+（持续更新） | pending（Phase 5） | 横向参考 |

## 00-overview 项目概览与学习指南（5篇）

| # | 原文标题 | 原文链接 | 本地文件 |
|---|---|---|---|
| 1 | 《AI大模型Ragent项目》构建真正的企业级RAG系统 | https://t.zsxq.com/LwR7k | `00-overview/00-01-构建真正的企业级RAG系统.md` |
| 2 | 《AI大模型Ragent项目》——如何在简历中呈现RAG项目？ | https://t.zsxq.com/h2E2h | `00-overview/00-02-如何在简历中呈现RAG项目.md` |
| 3 | 《AI大模型Ragent项目》——如何在简历中呈现RAG项目（v1.1升级版） | https://t.zsxq.com/dFYju | `00-overview/00-03-如何在简历中呈现RAG项目v1.1升级版.md` |
| 4 | 《AI大模型Ragent项目》——如何学习AI项目？ | https://t.zsxq.com/tJKcd | `00-overview/00-04-如何学习AI项目.md` |
| 5 | 《AI大模型Ragent项目》——简历上写AI项目，业务场景怎么选？ | https://t.zsxq.com/FrRBe | `00-overview/00-05-简历上写AI项目业务场景怎么选.md` |

（另有 1 个加群链接 https://t.zsxq.com/cw7b9 ，非文章内容，不纳入整理）

## 01-rag-fundamentals RAG与大模型基础概念（25篇）

| # | 原文标题 | 原文链接 | 本地文件 |
|---|---|---|---|
| 1 | 第1小节：认识大模型 | https://t.zsxq.com/qlL8D | `01-rag-fundamentals/01-01-认识大模型.md` |
| 2 | 第2小节：调用大模型API | https://t.zsxq.com/ykBXs | `01-rag-fundamentals/01-02-调用大模型API.md` |
| 3 | 第3小节：Prompt工程入门 | https://t.zsxq.com/S9lNL | `01-rag-fundamentals/01-03-Prompt工程入门.md` |
| 4 | 第4小节：什么是RAG？ | https://t.zsxq.com/wBsEm | `01-rag-fundamentals/01-04-什么是RAG.md` |
| 5 | 第5小节：用Apache Tika解析文档 | https://t.zsxq.com/lpyUl | `01-rag-fundamentals/01-05-用ApacheTika解析文档.md` |
| 6 | 第6小节：数据分块Chunk策略与实践 | https://t.zsxq.com/baxAh | `01-rag-fundamentals/01-06-数据分块Chunk策略与实践.md` |
| 7 | 第7小节：元数据的作用与管理 | https://t.zsxq.com/dOx4B | `01-rag-fundamentals/01-07-元数据的作用与管理.md` |
| 8 | 第8小节：从文本到向量之理解Embedding | https://t.zsxq.com/pCC2X | `01-rag-fundamentals/01-08-从文本到向量之理解Embedding.md` |
| 9 | 第9小节：向量数据库的原理与选型 | https://t.zsxq.com/5XiKR | `01-rag-fundamentals/01-09-向量数据库的原理与选型.md` |
| 10 | 第10小节：向量检索策略与召回优化 | https://t.zsxq.com/Sx4gA | `01-rag-fundamentals/01-10-向量检索策略与召回优化.md` |
| 11 | 第11小节：大模型生成策略与幻觉抑制 | https://t.zsxq.com/3v399 | `01-rag-fundamentals/01-11-大模型生成策略与幻觉抑制.md` |
| 12 | 第12小节：理解函数调用Function Call | https://t.zsxq.com/Sd930 | `01-rag-fundamentals/01-12-理解函数调用FunctionCall.md` |
| 13 | 第13小节：MCP协议入门与实践 | https://t.zsxq.com/xt3W0 | `01-rag-fundamentals/01-13-MCP协议入门与实践.md` |
| 14 | 深入解析MCP中的Resources与Prompts | https://t.zsxq.com/K7tkK | `01-rag-fundamentals/01-14-深入解析MCP中的Resources与Prompts.md` |
| 15 | MCP之官方Java-SDK深度解析 | https://t.zsxq.com/QvKb8 | `01-rag-fundamentals/01-15-MCP之官方Java-SDK深度解析.md` |
| 16 | 多轮对话记忆设计 | https://t.zsxq.com/mBDLI | `01-rag-fundamentals/01-16-多轮对话记忆设计.md` |
| 17 | 查询重写与语义增强机制 | https://t.zsxq.com/d5ini | `01-rag-fundamentals/01-17-查询重写与语义增强机制.md` |
| 18 | 意图识别与多路由调度策略 | https://t.zsxq.com/vYbpo | `01-rag-fundamentals/01-18-意图识别与多路由调度策略.md` |
| 19 | RAG模型检索生成评估与优化 | https://t.zsxq.com/jb2Ti | `01-rag-fundamentals/01-19-RAG模型检索生成评估与优化.md` |
| 20 | SSE协议与流式响应 | https://t.zsxq.com/IKMx1 | `01-rag-fundamentals/01-20-SSE协议与流式响应.md` |
| 21 | SpringBoot-SSE服务端实战 | https://t.zsxq.com/l9Xki | `01-rag-fundamentals/01-21-SpringBoot-SSE服务端实战.md` |
| 22 | （加餐）MCP协议规范：JSON-RPC 2.0标准说明 | https://t.zsxq.com/D65cm | `01-rag-fundamentals/01-22-MCP协议规范JSON-RPC2.0标准说明.md` |
| 23 | （加餐）为什么MCP不使用HTTP或gRPC？ | https://t.zsxq.com/oacL6 | `01-rag-fundamentals/01-23-为什么MCP不使用HTTP或gRPC.md` |
| 24 | （加餐）工具调用架构设计指南 | https://t.zsxq.com/cj66k | `01-rag-fundamentals/01-24-工具调用架构设计指南.md` |
| 25 | （加餐）工具调用稳定性与安全保障 | https://t.zsxq.com/FG5YI | `01-rag-fundamentals/01-25-工具调用稳定性与安全保障.md` |

## 02-bootstrap-and-deployment 本地开发与模型部署环境（10篇）

| # | 原文标题 | 原文链接 | 本地文件 |
|---|---|---|---|
| 1 | 本地开发环境搭建 | https://t.zsxq.com/YllVK | `02-bootstrap-and-deployment/02-01-本地开发环境搭建.md` |
| 2 | Docker本地中间件部署 | https://t.zsxq.com/QnBXt | `02-bootstrap-and-deployment/02-02-Docker本地中间件部署.md` |
| 3 | PostgreSQL数据库初始化 | https://t.zsxq.com/Ynima | `02-bootstrap-and-deployment/02-03-PostgreSQL数据库初始化.md` |
| 4 | 项目启动指南 | https://t.zsxq.com/Hkh2u | `02-bootstrap-and-deployment/02-04-项目启动指南.md` |
| 5 | 控制台功能的全面剖析 | https://t.zsxq.com/QOSTR | `02-bootstrap-and-deployment/02-05-控制台功能的全面剖析.md` |
| 6 | 项目模块介绍 | https://t.zsxq.com/uFeGe | `02-bootstrap-and-deployment/02-06-项目模块介绍.md` |
| 7 | 如何发起一次知识问答请求 | https://t.zsxq.com/zfpgj | `02-bootstrap-and-deployment/02-07-如何发起一次知识问答请求.md` |
| 8 | 为什么要本地部署大模型？ | https://t.zsxq.com/8VPnK | `02-bootstrap-and-deployment/02-08-为什么要本地部署大模型.md` |
| 9 | Ollama核心概念与架构 | https://t.zsxq.com/d4HI2 | `02-bootstrap-and-deployment/02-09-Ollama核心概念与架构.md` |
| 10 | Ollama安装与模型调用实战 | https://t.zsxq.com/frBrF | `02-bootstrap-and-deployment/02-10-Ollama安装与模型调用实战.md` |

## 03-knowledge-base AI知识库建设（11篇，done）

| # | 原文标题 | 原文链接 | 本地文件 |
|---|---|---|---|
| 1 | RAG知识库管理宏观设计 | https://t.zsxq.com/tqP4O | `03-knowledge-base/03-01-RAG知识库管理宏观设计.md` |
| 2 | 知识库文件上传大小限制原理 | https://t.zsxq.com/pFmQ6 | `03-knowledge-base/03-02-知识库文件上传大小限制原理.md` |
| 3 | 为什么上传30MB文件占了100MB内存？ | https://t.zsxq.com/QFETM | `03-knowledge-base/03-03-为什么上传30MB文件占了100MB内存.md` |
| 4 | 文件上传分布式限流如何做？ | https://t.zsxq.com/K3M23 | `03-knowledge-base/03-04-文件上传分布式限流如何做.md` |
| 5 | 分布式限流选业务层还是网关层？ | https://t.zsxq.com/kJVbI | `03-knowledge-base/03-05-分布式限流选业务层还是网关层.md` |
| 6 | 知识库文档上传接口 | https://t.zsxq.com/NaoUi | `03-knowledge-base/03-06-知识库文档上传接口.md` |
| 7 | 知识库文档开始分块接口 | https://t.zsxq.com/FtWRt | `03-knowledge-base/03-07-知识库文档开始分块接口.md` |
| 8 | 深度解析知识库定时同步的架构设计 | https://t.zsxq.com/YMhqa | `03-knowledge-base/03-08-深度解析知识库定时同步的架构设计.md` |
| 9 | 定时同步的调度引擎与故障恢复 | https://t.zsxq.com/e9kKG | `03-knowledge-base/03-09-定时同步的调度引擎与故障恢复.md` |
| 10 | 知识库文档管理接口 | https://t.zsxq.com/eGdy4 | `03-knowledge-base/03-10-知识库文档管理接口.md` |
| 11 | 知识库数据分块管理接口 | https://t.zsxq.com/fn2gz | `03-knowledge-base/03-11-知识库数据分块管理接口.md` |

## 04-model-scheduling 大模型调度引擎（8篇，pending）

| # | 原文标题 | 原文链接 |
|---|---|---|
| 1 | AI基础设施层宏观设计 | https://t.zsxq.com/xmGYm |
| 2 | 多模型路由与智能选择 | https://t.zsxq.com/yJRRN |
| 3 | 三态熔断器与故障转移 | https://t.zsxq.com/C0btU |
| 4 | Chat同步调用与模板方法 | https://t.zsxq.com/3bUOG |
| 5 | SSE流式解析与异步执行 | https://t.zsxq.com/QHX9s |
| 6 | 流式路由的首包探测机制 | https://t.zsxq.com/gYKNI |
| 7 | Embedding向量化客户端 | https://t.zsxq.com/k0zXf |
| 8 | Rerank重排序与辅助工具 | https://t.zsxq.com/xKwgo |

## 05-qa-pipeline AI知识问答核心链路（18篇，pending）

| # | 原文标题 | 原文链接 |
|---|---|---|
| 1 | 知识问答在后端经历了哪八个阶段？ | https://t.zsxq.com/pVQt8 |
| 2 | 大模型没有记忆多轮对话怎么做到不失忆? | https://t.zsxq.com/WFr43 |
| 3 | 聊了50轮Token爆了记忆该压缩还是该丢 | https://t.zsxq.com/5lxZo |
| 4 | 用户说的话 ≠ 该搜的词 | https://t.zsxq.com/rCa8F |
| 5 | 四分类撑不住20个知识库为什么要设计意图树 | https://t.zsxq.com/NuEaK |
| 6 | 怎么让大模型同时给30个意图节点打分 | https://t.zsxq.com/VnGnQ |
| 7 | 三个子问题命中了八个意图，该保留哪几个 | https://t.zsxq.com/vwZle |
| 8 | 用户问退货政策，3C、家电和服装都举手了 | https://t.zsxq.com/Q19YN |
| 9 | 意图分数出来了，该查哪个库、查多少条 | https://t.zsxq.com/Qokj8 |
| 10 | 单次提问同时查三个知识库——多通道并行检索架构 | https://t.zsxq.com/dWyw9 |
| 11 | 三个通道返回30条结果，最终只给模型5条 | https://t.zsxq.com/XsbNY |
| 12 | 知识库答不了的问题，交给MCP工具去查 | https://t.zsxq.com/mfPoB |
| 13 | 用户只说了一句话，工具需要的参数从哪来 | https://t.zsxq.com/X7tda |
| 14 | 检索结果、工具数据、对话历史——最终的Prompt怎么拼 | https://t.zsxq.com/Y80Y3 |
| 15 | 答案一个字一个字蹦出来流式生成的完整链路 | https://t.zsxq.com/Dl6F2 |
| 16 | 用户点了停止生成集群里发生了什么 | https://t.zsxq.com/dqWjK |
| 17 | 10个人同时提问只有3个坑位 | https://t.zsxq.com/m4blA |
| 18 | 抢不到许可请求该等还是该拒？ | https://t.zsxq.com/mOutN |

## 06-evaluation RAG评测体系（11篇，pending）

| # | 原文标题 | 原文链接 |
|---|---|---|
| 1 | RAG 做出来了，但效果怎么衡量？ | https://t.zsxq.com/0mYZD |
| 2 | 评估集没建好，RAG 评测都是白搭 | https://t.zsxq.com/YzTo8 |
| 3 | 测评数据初始化 | https://t.zsxq.com/1OfHs |
| 4 | 单次问答背后的全链路 | https://t.zsxq.com/jCALF |
| 5 | 从意图到检索：指标拆解 | https://t.zsxq.com/txWHU |
| 6 | 性能指标的口径选择 | https://t.zsxq.com/jrYMw |
| 7 | RAGAS是什么与选型 | https://t.zsxq.com/nUK3K |
| 8 | 把RAGAS跑起来 | https://t.zsxq.com/25Iqw |
| 9 | RAGAS五个指标全解读 | https://t.zsxq.com/PaWec |
| 10 | RAGAS的坑 | https://t.zsxq.com/8YZhT |
| 11 | 测评报告 | https://t.zsxq.com/PrCzD |

## 07-interview 面试高频考点（3篇+持续更新，pending）

| # | 原文标题 | 原文链接 |
|---|---|---|
| 1 | 为什么不使用SpringAI或LangChain4j？ | https://t.zsxq.com/OeRxm |
| 2 | 为什么不使用Python实现RAG？ | https://t.zsxq.com/NJTs5 |
| 3 | Ragent为什么从Milvus换成Pgvector？ | https://t.zsxq.com/fpIYO |
