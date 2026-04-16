# xRag Phase 2C PRD

**日期：** 2026-04-16
**版本：** `v6 / Phase 2C`
**状态：** active
**上游基线：** [v5 Handoff](/Users/coderlauu/xRag/docs/handoff/v5.md), [v5 Status](/Users/coderlauu/xRag/docs/status/v5-phase-2b.md), [Phase 2B Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-11-xrag-phase-2b-backlog.md), [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)

---

## 1. 一句话定位

在 `Phase 2B` 已完成可信问答与可控问答后，`Phase 2C` 不再优先扩展新的问答花样，而是把“问答质量治理与运行治理”产品化，让产品、工程和运维能稳定回答四个问题：现在能不能答、为什么不能答、质量是否在漂移、下一步该先处理什么。

---

## 2. 背景

`Phase 2A / 2B` 已经证明两层能力成立：

- 面向用户的可信问答闭环已经可用，且 `citation / refusal / freshness / release-readiness` 已冻结为正式边界
- 面向内部的运维与排障资产已经有最小基线，包括 `ops` 页面、deployment 摘要、health summary、incident 列表、answer summary 以及 `Phase 2A Evaluation Plan`

但当前仍存在明显产品缺口：

- `ops` 页面已经能看摘要，却还不能稳定回答“当前 Ask 失败到底是索引底料不足、质量退化、服务异常还是最近部署带来的回归”
- `Phase 2A Evaluation Plan` 已定义 `recall / groundedness / citation_coverage / refusal_precision / latency / cost / backlog / freshness_lag`，但这些口径还没有真正变成日常产品面板
- 最近实际运行中已经暴露出“文档都停在 not_indexed，Ask 根本无法 retrieval”“GitHub Actions 全绿但生产仍可能不健康”这类问题，说明光有实现和 smoke 还不够，还缺少面向运行者的治理视角
- 当前 `ops` 板仍偏向工程摘要，缺少更明确的质量分层、问题聚类和处置优先级

因此 `Phase 2C` 的目标不是重新打开 `Phase 2B` 已完成的 `scope / retrieval explain / evidence / history` 主线，而是把 `Phase 2B` 留下的 `P1-01` 和运行治理输入正式收敛成下一版本主线。

---

## 3. 本期目标

- 把 `Phase 2A Evaluation Plan` 中的核心指标产品化为统一质量面板
- 把 `index readiness / freshness / backlog / failure` 提升为正式的可视状态，而不是隐含在运维排障里
- 把 incidents 从“平铺日志”升级为“按来源、影响面和推荐动作聚类”的治理入口
- 建立“质量漂移是否与最近部署、最近事件、最近索引异常相关”的可读链路
- 在不改变 `Ask / Search / Detail` 主信任边界的前提下，让内部团队更快判断版本是否健康、问题集中在哪一层

---

## 4. 非目标

- 新的问答主体验能力，不重新打开 `Phase 2B` 的 `scope / history / evidence / retrieval explain` 主线
- 开放互联网联网回答
- 多轮 assistant memory、自动扩 scope、自动补资料
- 新的多模型路由平台
- 团队协作 / 权限模型
- 浏览器插件
- 移动端
- 自动修复、自动回滚或自动重建索引工作流

`Phase 2C` 先解决“看清和治理”，不承诺“自动代劳”。

---

## 5. 目标用户

### 主用户

- 产品负责人
- 工程负责人
- 值班运维 / 值班开发

### 次级用户

- 需要验证 release 是否仍满足可信问答门槛的 QA / 内测人员

### 非目标用户

- 只关心直接提问结果的一般终端用户

`Phase 2C` 是内部治理版本，不是面向终端用户的大交互重构版本。

---

## 6. 核心场景

### 场景 1：判断当前知识库是否“可问”

值班者进入系统后，需要在几十秒内知道：

- 当前可回答文档有多少
- 有多少文档停在 `queued / chunking / embedding / stale / failed`
- 当前 Ask 失败是否主要由 corpus readiness 不足引起

### 场景 2：判断问答质量是否在漂移

产品和工程需要看到：

- groundedness 是否明显下降
- citation coverage 是否回退
- refusal precision 是否失真
- latency / cost 是否超预算

### 场景 3：定位问题主要集中在哪一层

运维者需要快速判断当前问题更像是：

- 索引底料不足
- retrieval / answer 质量回退
- provider / runtime 服务异常
- 最近部署引入的问题

### 场景 4：从异常聚类切到处置动作

当 incidents 增多时，值班者希望看到：

- 失败主要来源于 `upload / parse / ocr / fetch / projection / deploy / ci` 哪一类
- 哪一类问题影响面最大、优先级最高
- 当前应先做什么，而不是从原始日志开始读起

### 场景 5：判断一次发布是否仍然安全

在 release 后，团队希望从一个板上快速回答：

- 最新部署是否已通过 smoke
- 当前质量指标是否与最近部署时间发生相关变化
- 是否需要回滚或保持观察

---

## 7. 优先级原则

1. 先回答“系统现在是否健康且可问”，再回答“更细的趋势细节”
2. 指标口径只允许沿用 [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)，不得新造第二套产品定义
3. 治理面板必须建立在真实运行事实之上，不能为展示效果生成第二套 explain 数据
4. `ops` 是内部治理入口，不得反向改写 `Ask / Search / Detail` 的主用户心智
5. 若某项治理增强会要求重新定义 `citation / refusal / freshness / release-readiness`，优先降 scope

---

## 8. 需求细化与优先级拆分

### 8.1 P0 核心交付

| ID | 能力 | 需求细化 | 为什么是 P0 |
| --- | --- | --- | --- |
| `P0-01` | 语料就绪度面板 | 把 `ready / backlog / stale / failed / freshness lag` 做成一眼可读的 corpus readiness 视图，并明确 Ask 是否受其阻断。 | 运行中最容易误判的问题就是“代码没坏，但语料还不可问”。 |
| `P0-02` | 质量评分卡与趋势 | 把 `groundedness / citation_coverage / refusal_precision / latency / cost` 变成统一质量板和趋势视图。 | 没有统一质量口径，就无法判断版本是否回退。 |
| `P0-03` | Incident 聚类与推荐动作 | 把 incident 从单条列表升级为按来源、严重度、影响面聚类，并给出处置优先级。 | 当前失败排查仍过于依赖人工读日志。 |
| `P0-04` | 发布关联与健康守门 | 把最新部署、smoke、核心服务健康和质量漂移放到同一视图，支持发布后快速判断。 | 当前 release-readiness 只在流程里存在，还没有充分产品化。 |

### 8.2 P1 补强

| ID | 能力 | 需求细化 | 为什么下放到 P1 |
| --- | --- | --- | --- |
| `P1-01` | Drill-down 与样本回放 | 从趋势或 incident 进入单次会话、单个异常样本、单次索引链路的细粒度回放。 | 价值明确，但先把聚合治理和主板口径做稳更重要。 |
| `P1-02` | 成本与质量联动分析 | 支持按 provider / model / route 维度看成本与质量变化。 | 当前仓库尚未冻结多模型治理边界，过早进入会扩大范围。 |

### 8.3 明确延后

以下能力统一放到后续版本候选，不进入 `Phase 2C`：

- 自动索引修复、自动回滚、自动告警编排
- 在线 golden set 编辑器
- 团队协作型看板
- 新的问答主入口、摘要、推荐、多模型对比
- 联网问答与 agent 工作流

---

## 9. 阶段切片建议

### Slice A：就绪度与质量主板

先交付：

- `P0-01` 语料就绪度面板
- `P0-02` 质量评分卡与趋势

目标：

- 先让团队能稳定判断“当前能不能答”和“质量是否在漂移”。

### Slice B：异常治理与发布关联

继续补齐：

- `P0-03` Incident 聚类与推荐动作
- `P0-04` 发布关联与健康守门

目标：

- 让值班者能从“看到异常”快速走到“知道先处理什么”。

### Slice C：深钻与样本回放

最后再补：

- `P1-01` Drill-down 与样本回放

目标：

- 在聚合面板稳定后，再做更细的诊断入口。

---

## 10. 验收标准

### 10.1 Phase 2C Gate

1. 值班者能在一个板上判断当前 Ask 是否被 corpus readiness、服务健康、部署回归或质量退化阻断。
2. 指标名和口径与 [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md) 一致，不出现第二套定义。
3. `ready / backlog / stale / failed / freshness lag` 被提升为正式产品事实，而不是只存在实现日志或排障命令里。
4. incident 能按来源和优先级聚类展示，不再只是平铺列表。
5. 最新部署、smoke 结果、核心服务健康和质量摘要能在同一视图中共同判断 release 健康度。

### 10.2 Phase 2C 完整度补强

1. 团队能从趋势或 incident 快速进入需要的 drill-down，而不是重新拼事实链。
2. `ops` 板增强不会让终端用户误以为问答系统新增了联网、自动代理或多轮记忆能力。
3. 本轮增强不会反向破坏 `Ask / Search / Detail` 主链的稳定性和可信边界。

---

## 11. 对技术方案评估的要求

进入 `technical-evaluation` 前后，必须回答：

- corpus readiness、质量指标、incident、deployment 之间的正式事实源如何对齐
- 哪些指标可以实时计算，哪些需要预聚合
- incident 聚类的最小稳定分类是什么，如何避免被临时日志文本绑死
- `ops` 页面与现有 `answer-summary / health-summary / incidents / deployments` 资源如何扩展，而不是重造第二套接口
- 哪些状态需要向 `Ask / Search / Detail` 暴露轻量提示，哪些必须严格留在内部治理面板

这些问题在 `Phase 2C` 不应直接跳过；没有冻结这些边界，就不进入实现 lane。
