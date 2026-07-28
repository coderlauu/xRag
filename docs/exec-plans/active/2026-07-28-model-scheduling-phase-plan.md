# Exec Plan: Phase 2 大模型调度引擎

## 1. Metadata

- `phase`: Phase 2 / 大模型调度引擎
- `status`: in-progress（Step A complete）
- `created`: 2026-07-28
- `owner`: liuqiang
- `parent`: [总执行计划](2026-07-24-ragent-column-learning-and-system-build.md)
- `source_group`: [04-model-scheduling](../../../learning/ragent-column/INDEX.md)
- `predecessor`: [Phase 1 里程碑状态](../../status/2026-07-28-phase-1-knowledge-base.md)

## 2. Objective

学习并验证专栏“大模型调度引擎”分组的 8 篇内容，在 xrag 中形成统一的 AI 基础设施层：上层业务只面向稳定接口调用 Chat、流式 Chat、Embedding 和 Rerank；调度层负责模型能力匹配、路由、熔断、故障转移和可诊断记录。

Phase 2 完成后，至少能演示：

> Chat、流式 Chat、Embedding 和 Rerank 通过各自稳定端口进入同一 AI 基础设施层 → 根据能力和策略选择 Provider/模型 → 首选模型失败或熔断时按契约切换 → 同步或 SSE 流式返回结果 → 调用记录能解释“选了谁、为什么切换、耗时多少”。

## 3. 边界

### In Scope

- Provider、模型和能力的统一领域模型。
- Chat 同步调用与公共执行模板。
- 多模型路由、三态熔断器与故障转移。
- SSE 流式解析、首包探测、超时和取消传播。
- Embedding 与 Rerank 的统一客户端接口。
- 把 Phase 1 已有的 Embedding 调用收敛到共享 AI 基础设施层，保持知识库行为不回归。
- 调度决策、失败原因、首包耗时和总耗时的可诊断记录。

### Out Of Scope

- 对话记忆、查询改写、意图识别、多知识库检索、Prompt 组装、MCP 工具调用和最终问答编排——这些属于 Phase 3。
- 检索与生成质量评测——属于 Phase 4。
- 多租户、计费、配额售卖和复杂权限体系。
- 在完成 Step A 学习和 Step B PRD 前预先冻结 Schema、API 或具体熔断库。

### 用户界面判断

Phase 2 的核心消费者是 Phase 3 后端，但路由和熔断状态具有明显的运维可视化价值。项目所有者已于 2026-07-28 确认采用推荐方案：

- 做一个轻量“模型调度诊断页”，展示 Provider/模型能力、候选过滤原因、熔断状态、最近调用与故障转移。
- 不做聊天产品页，不扩张为模型 CRUD、计费或复杂权限后台。
- 默认不保存或展示 Prompt、文档正文和模型输出，只记录调度事实和脱敏请求标识。

`ui-spec.md` 与可点击原型仍是前端实现前置闸门；具体字段、保留期和查看权限由 Step B 确认。

## 4. Plan

### Step A — 学习整理与检查点

状态：**complete（2026-07-28）**

按顺序完成 8 篇专题：

1. AI 基础设施层宏观设计。
2. 多模型路由与智能选择。
3. 三态熔断器与故障转移。
4. Chat 同步调用与模板方法。
5. SSE 流式解析与异步执行。
6. 流式路由的首包探测机制。
7. Embedding 向量化客户端。
8. Rerank 重排序与辅助工具。

产出：

- [x] `learning/ragent-column/04-model-scheduling/04-01...04-08` 学习整理稿。
- [x] [Phase 2 Step A 学习检查点](../../../learning/ragent-column/PHASE-2-STEP-A-CHECKPOINT.md)：课程方案、xrag 当前实现、可复用部分和待决策问题的对照。
- [x] [一手资料复核](../../research/2026-07-28-model-scheduling-primary-source-review.md)。
- [x] 更新 `learning/ragent-column/INDEX.md`：59/91。

闸门：项目所有者确认学习结论后进入 Step B。

### Step B — PRD 与界面范围

先定义调度引擎服务谁、解决什么问题，再讨论实现：

- 定义模型能力、路由策略、故障转移和可解释性的产品规则。
- 明确 Provider/模型配置是文件配置还是可运行时管理。
- 明确失败时由系统自动切换还是向调用方暴露选择。
- 冻结已确认的轻量诊断页字段、保留期、查看权限和脱敏规则。
- 产出 PRD、界面规格与可点击原型。

闸门：PRD 必须确认；若有界面，原型必须在写前端代码前确认。

### Step C — 技术方案与执行拆分

产出：

- `tech/model-scheduling/architecture.md`
- `tech/model-scheduling/data-model.md`
- `tech/model-scheduling/api.md`
- 必要的 ADR
- 分角色任务书、垂直切片工单和测试矩阵

必须在本 Step 决策：

- 能力模型与 Provider SPI 的最小接口。
- 路由输入、确定性优先级和随机/权重策略是否需要。
- 熔断状态存放位置、时间窗口和单实例/多实例语义。
- 同步与流式调用能否共享执行骨架，哪些阶段必须分开。
- SSE 首包失败与流中失败的故障转移边界。
- Phase 1 `EmbeddingClient` 的兼容迁移路径。
- 外部模型供应商的条款、限额、费用和测试凭据。

闸门：技术方案及关键取舍经项目所有者确认后进入 Step D。

### Step D — 垂直切片实现

预期按以下能力切片，最终顺序由 Step C 的依赖图确定：

1. Provider/模型/能力配置与只读查询。
2. 统一调用上下文和同步 Chat 最小链路。
3. 可解释的路由选择。
4. 熔断状态机与同步故障转移。
5. SSE 流式解析、首包探测、取消和超时。
6. 流式路由与故障转移。
7. Embedding 迁移并回归 Phase 1 入库。
8. Rerank 接口与实现。
9. 诊断记录及经确认的运维页面。

闸门：浏览器或 API 演示统一入口、选路、熔断和故障转移。

### Step E — 测试矩阵执行

最低覆盖：

- 路由在相同输入下可确定复现。
- Provider 不支持所需能力时不会被选中。
- 熔断 `CLOSED → OPEN → HALF_OPEN → CLOSED/OPEN` 全状态转换。
- 首选失败后只切到满足能力约束的候选模型。
- SSE 分片、跨 chunk JSON、首包超时、流中断、取消和资源释放。
- 非限流错误不盲目重试；限流和瞬时错误遵循已确认策略。
- 外部网络调用不进入数据库长事务。
- Phase 1 知识库向量化回归。
- 至少一次真实 Provider 的同步、流式、Embedding/Rerank 能力验证；不支持的能力必须明确记录而非伪造通过。

闸门：自动化与手工结果全部回写测试矩阵，并由项目所有者确认。

### Step F — CI/CD 与部署配置

- CI 使用可编程假 Provider，不调用收费 API。
- 真实 Provider 测试由显式环境变量守门。
- 部署示例登记 Provider、模型、超时、重试、熔断和路由配置，不提交真实密钥。
- 把 Phase 1 远端 CI 递延项与 Phase 2 新增门禁分开记录，避免旧问题污染新模块的完成口径。

### Step G — 复盘与里程碑状态

- 产出 `docs/retro/` 与 `docs/status/`。
- 记录真实故障转移证据、保留风险和 Phase 3 的消费契约。
- 经项目所有者确认后关闭 Phase 2。

## 5. Success Criteria

- 上层业务不依赖具体 Provider SDK 或供应商错误结构。
- 同步 Chat、流式 Chat、Embedding 和 Rerank 都通过各自稳定端口进入统一基础设施层。
- 路由结果可解释，故障转移有确定候选边界。
- 熔断状态机有自动化测试，不靠日志目测。
- SSE 取消后连接、线程和许可均被释放。
- Phase 1 知识库入库迁移后行为和向量不变量不回归。
- 外部 API 条款与限额在编码前完成准入检查。

## 6. Risks

| 风险 | 处理 |
|---|---|
| 8 篇课程方案与 xrag 现有 `EmbeddingClient` 重叠 | Step A 对照后决定复用或迁移，不并存两套基础设施 |
| 流式响应中途失败后无法安全切换 | Step C 明确“首包前可切换、首包后如何处理”的产品契约 |
| 熔断状态在多实例下不一致 | 先依据部署拓扑决策，不能默认本地内存实现等价于分布式 |
| 真实 Provider 能力和限额不一致 | 设计前核对官方条款与接口，测试按能力逐项守门 |
| 诊断页膨胀成完整管理后台 | 只展示调度事实；模型 CRUD、权限和计费不自动纳入 |

## 7. Current Node

- `now`: Phase 2 Step A 已完成：8/8 学习稿、一手资料复核、对齐检查点和 59/91 索引已落地；没有修改业务代码。
- `next`: 项目所有者确认 Step A 学习结论后进入 Step B，先产出 PRD、诊断页界面规格和可点击原型。
- `decision_needed`: Step B 需要确认调用方选具体模型还是声明能力、自动故障转移边界、流提交后中断语义、配置管理方式，以及诊断记录的保留期/权限/脱敏规则。
