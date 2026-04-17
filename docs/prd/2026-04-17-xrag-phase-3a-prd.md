# xRag Phase 3A PRD

**日期：** 2026-04-17
**版本：** `v7 / Phase 3A`
**状态：** active
**上游基线：** [v6 Handoff](/Users/coderlauu/xRag/docs/handoff/v6.md), [v6 Status](/Users/coderlauu/xRag/docs/status/v6-phase-2c.md), [Phase 2C Backlog](/Users/coderlauu/xRag/docs/prd/2026-04-16-xrag-phase-2c-backlog.md), [Phase 2A Evaluation Plan](/Users/coderlauu/xRag/docs/process/2026-04-07-phase-2a-evaluation-plan.md)

---

## 1. 一句话定位

在 `Phase 2C` 已把质量治理与运行治理做成统一主板后，`Phase 3A` 不再只回答“现在哪里有问题”，而是把“从问题一路追到根因”的诊断与样本回放工作流产品化。

---

## 2. 背景

`Phase 2C` 已经解决了内部团队最关键的一层问题：

- 可以在一个治理主板上判断系统当前是否可问
- 可以读到 corpus readiness、runtime quality、incident cluster、release guard 的聚合事实
- 可以把 deployment / evaluation facts 正式写入并作为 release gate 读取

但当前仍存在明显的产品缺口：

- `ops` 主板能回答“现在不健康”，却还不能稳定回答“到底是哪批 session、哪条 indexing 链路、哪次发布窗口在出问题”
- incident 已有聚类，但缺少从聚类进入具体样本、具体会话、具体文档链路的诊断闭环
- `Phase 2A Evaluation Plan` 已给出质量指标口径，但还没有稳定的样本级回放工作流，团队仍需在页面、脚本、日志和数据库之间来回切换
- 真实运行中已经存在 `db.xrag.coderlau.cn`、PostgreSQL `127.0.0.1:5432` 回环映射、CI artifacts、production inspection guide 等排障入口，但这些入口尚未形成一致的产品工作流

因此 `Phase 3A` 的目标不是继续扩新的终端问答玩法，而是把 `Phase 2C` 留下的 `P1-01 Drill-down 与样本回放` 正式收敛为下一版本主线，并只在边界稳定后再考虑 `P1-02 成本与质量联动分析`。

---

## 3. 本期目标

- 把 `ops` 从聚合治理主板推进为可深钻的诊断入口
- 让团队可以从趋势、incident 或 release risk 直接进入单个样本、单次 session、单次 indexing 链路
- 建立 answer session replay、document/indexing replay 和 deployment window compare 的统一工作流
- 在不改变 `Ask / Search / Detail` 主信任边界的前提下，让内部团队更快定位回归和运行债务
- 修复 Ask active-session stuck polling 可靠性缺口，确保长时间 active 的 answer session 有服务端终态收口和前端轮询兜底

---

## 4. 非目标

- 新的终端问答主体验能力，不重新打开 `Phase 2B` 的 `scope / history / evidence / retrieval explain` 主线
- 自动 remediation、自动 rerun、自动回滚
- 在线 golden set 编辑器
- 团队协作 / 权限模型
- 浏览器插件
- 移动端
- 新的多模型治理平台
- 开放互联网联网回答

`Phase 3A` 先解决“可追踪、可回放、可定位根因”，不承诺“自动替团队执行处置”。

---

## 5. 目标用户

### 主用户

- 产品负责人
- 工程负责人
- 值班运维 / 值班开发

### 次级用户

- 需要验证某次质量退化、部署回归或 indexing 异常的 QA / 内测人员

### 非目标用户

- 只关心直接提问结果的一般终端用户

`Phase 3A` 依然是内部诊断版本，不是面向终端用户的大交互改版。

---

## 6. 核心场景

### 场景 1：从质量趋势进入具体异常样本

值班者看到 groundedness、citation coverage 或 incident cluster 异常后，需要在几十秒内跳到具体样本，而不是重新在 session、日志和数据库里手工拼线索。

### 场景 2：回放单次 answer session

工程和产品需要看到：

- 当时的问题、scope snapshot 和时间窗口
- retrieval items 与 why-not-in-answer
- citations、freshness、refusal / failure 结果
- 该次 session 与当前 incident / deployment 的关系

### 场景 3：回放单次 document / indexing 链路

当 corpus readiness 出现下降时，团队需要快速判断：

- 问题出在 `upload / parse / ocr / projection / indexing / stale`
- 哪个 document 或哪批 document 受影响
- 当前链路是 backlog、失败还是 freshness 漂移

### 场景 4：判断某次发布是否导致样本级回归

发布后，团队希望快速回答：

- 最近 deploy 前后，哪些质量指标变化最大
- 哪些 incident cluster 或样本是在该窗口后首次显著出现
- 是否应该继续观察、人工回滚，还是先处理既有运行债务

---

## 7. 优先级原则

1. 先解决“怎么追到根因”，再补更深的运营分析
2. 诊断工作流必须复用真实事实源，不允许新造影子 explain 数据
3. `ops` 深钻仍然是内部工作流，不反向改写 `Ask / Search / Detail` 的终端心智
4. 若某项诊断增强要求重新定义 `citation / refusal / freshness / release-readiness`，优先降 scope
5. 自动化处置不进入 `P0`

---

## 8. 需求细化与优先级拆分

### 8.1 P0 核心交付

| ID | 能力 | 需求细化 | 为什么是 P0 |
| --- | --- | --- | --- |
| `P0-01` | 样本级 drill-down 入口 | 从趋势、incident cluster、release guard 进入具名样本列表，并保留窗口、来源和风险上下文。 | `Phase 2C` 已能看聚合，但还不能稳定跳到具体异常样本。 |
| `P0-02` | Answer session 回放 | 回放单次 session 的 query、scope snapshot、retrieval、citation、freshness、refusal / failure 状态与时间线。 | 当前最常见的质量回归仍发生在 session 级，而不是纯聚合数字级。 |
| `P0-03` | Document / indexing 链路回放 | 回放单个 document 或批次在 `upload -> processing -> indexing -> stale/failure` 的关键状态与阻断点。 | 当前 readiness 问题仍需要跨脚本和数据库手工排查。 |
| `P0-04` | 发布窗口对比与受影响样本定位 | 支持围绕最新 deployment 观察 before/after 窗口，并关联异常样本与质量漂移。 | 只有聚合 release guard 还不够，团队仍需要定位“到底哪些样本变坏了”。 |
| `P0-G1` | Ask active-session 终态收口与轮询兜底 | 长时间停留在 `idle / retrieving / synthesizing` 的 session 必须由服务端收口到 `failed`，Ask 页面必须停止无限轮询并给出可解释提示。 | 永久检索中会破坏 Ask 主链路，也会让 session replay 和 diagnostic sample 失去可信终态。 |

### 8.2 P1 补强

| ID | 能力 | 需求细化 | 为什么下放到 P1 |
| --- | --- | --- | --- |
| `P1-01` | 成本与质量联动分析 | 支持按 provider / model / route 维度看质量与成本权衡。 | 当前仓库尚未冻结多模型治理边界，过早进入会扩大范围。 |
| `P1-02` | 运维入口联动 | 在 drill-down 内直接串联 pgweb、CI artifacts、inspection guide 等现有入口。 | 很有用，但优先级低于先把诊断对象和回放视图做稳。 |

### 8.3 明确延后

以下能力统一放到后续版本候选，不进入 `Phase 3A`：

- 自动修复、自动回滚、自动重建索引
- 自由文本日志检索平台
- 在线 golden set 编辑与标注系统
- 团队协作型值班看板
- 新的 Ask 主交互重构
- 摘要、推荐、多模型路由、联网回答

---

## 9. 阶段切片建议

### Slice A：样本入口与 session 回放

先交付：

- `P0-01` 样本级 drill-down 入口
- `P0-02` Answer session 回放

目标：

- 先让团队能从趋势或 incident 快速进入具体问答样本。

### Slice B：文档链路回放与发布窗口对比

继续补齐：

- `P0-03` Document / indexing 链路回放
- `P0-04` 发布窗口对比与受影响样本定位

目标：

- 把样本诊断从 session 扩到 corpus 与 deploy 维度，形成完整根因链。

### Slice C：成本联动与运维入口桥接

最后再补：

- `P1-01` 成本与质量联动分析
- `P1-02` 运维入口联动

目标：

- 在诊断主链稳定后，再补更多内部优化入口。

---

## 10. 验收标准

### 10.1 Phase 3A Gate

1. 值班者能从趋势、incident 或 release guard 直接进入具体异常样本，而不需要先离开产品界面手工拼线索。
2. 团队能在一个内部工作流里回放单次 answer session，并看到 query、scope、retrieval、citation、freshness 和 terminal state。
3. 团队能回放单次 document / indexing 链路，并判断问题更像 backlog、失败、stale 还是 freshness 漂移。
4. release 对比至少能回答“最近 deploy 后，哪些样本或 cluster 变坏了”，而不是只停留在聚合摘要。
5. Ask 页面不会因为 answer session 长期停留在 active status 而无限轮询；服务端必须把不可恢复 active session 收口为现有 terminal status。

### 10.2 Phase 3A 完整度补强

1. `drill-down / replay` 不会反向污染 `Ask / Search / Detail` 的终端心智。
2. 诊断工作流不会生成第二套与真实 facts 不一致的 explain 数据。
3. 本轮增强不会反向破坏 `Phase 2A / 2B / 2C` 已冻结的可信边界。

---

## 11. 对技术方案评估的要求

进入 `technical-evaluation` 前后，必须回答：

- sample、session、document、deployment 四类对象的正式事实源如何对齐
- 哪些回放视图可以实时拼装，哪些需要预聚合或快照
- `ops` 下的新深钻资源是扩现有 `/ops/*` 资源族，还是增加新的内部诊断资源
- `pgweb`、PostgreSQL 回环映射、CI artifacts、inspection guide 等现有入口应如何进入产品工作流，而不是继续停留在口口相传
- 哪些诊断状态允许向 `Ask / Search / Detail` 暴露轻量入口，哪些必须严格留在内部诊断面板

这些问题在 `Phase 3A` 不应直接跳过；没有冻结这些边界，就不进入实现 lane。
