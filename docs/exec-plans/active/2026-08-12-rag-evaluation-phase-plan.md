# Exec Plan: Phase 4 RAG 评测体系

## 1. Metadata

- `phase`: Phase 4 / RAG 评测体系
- `status`: active / Step C（`2026-08-13`）
- `owner`: liuqiang
- `parent`: [总执行计划](2026-07-24-ragent-column-learning-and-system-build.md)
- `predecessor`: [Phase 3 里程碑状态](../../status/2026-08-12-phase-3c-generation-streaming.md)
- `source_group`: [06-evaluation](../../../learning/ragent-column/INDEX.md)（11 篇）

## 2. Objective

在 Phase 3 可回答链路之上建立可复现、可比较、能解释退化来源的 RAG 评测体系。评测结果必须绑定
数据集、知识库 revision、模型身份与 Prompt 版本，分别衡量链路正确性、检索质量、生成质量、性能与
人工偏好，不能用一个总分掩盖各阶段问题。

## 3. 当前边界

Step A/B 已关闭。当前 Step C 设计架构、数据模型、API、ADR、测试矩阵、分角色任务书与原子工单；
不写生产代码。第一版不做专用页面，RAGAS 保持候选并隔离在框架无关 seam 之后。

## 4. A~G 闸门

| Step | 产出 | 当前状态 | 用户确认 |
|---|---|---|---|
| A | 11 篇学习稿、一手资料复核、Phase 4 对齐检查点 | **complete** | 是（`2026-08-13`） |
| B | PRD；是否需要界面的显式裁决；若需要则含 ui-spec 与可点击原型 | **complete** | 是（`2026-08-13`） |
| C | 架构、数据模型、API、ADR、测试矩阵、分角色任务书与原子工单 | **in progress** | 是 |
| D | 纵向切片实现 | pending | 是 |
| E | 自动化、真实样本与人工评测矩阵执行 | pending | 是 |
| F | CI/CD、费用守门、评测数据保留与部署配置 | pending | 否 |
| G | 复盘、里程碑状态与 Phase 5 准入 | pending | 是 |

## 5. Step A — 学习与事实基线

### 5.1 输入

- [06-evaluation 11 篇原文目录](../../../learning/ragent-column/INDEX.md)
- Phase 3 的 3A/3B/3C 测试矩阵、状态文档与递延项
- RAGAS 等候选方案的官方文档和论文；二手课程内容不能单独作为技术事实依据

### 5.2 必须回答的问题

1. 一条评测样本的最小不可变身份是什么，如何绑定数据集、知识库、模型、Prompt 与代码版本？
2. 意图、检索、工具、生成和端到端分别用什么指标，哪些指标不能跨阶段混用？
3. 无参考答案、弱标注、人工金标三类数据各能证明什么，如何防止评测集污染？
4. 离线质量、在线性能、费用与可靠性如何分层记录，怎样定位一次退化来自哪个环节？
5. LLM-as-judge 的模型、Prompt、随机性、偏置和费用如何版本化与守门？
6. RAGAS 的版本、指标前提与中文适用性是否满足本项目，哪些地方需要自有指标或人工复核？
7. 第一版是否需要评测页面，还是报告产物与 API 已足够？这个判断必须提交项目所有者裁决。

### 5.3 产出与完成定义

- [x] `learning/ragent-column/06-evaluation/` 下 11 篇学习稿全部完成，INDEX 更新为 11/11。
- [x] `docs/research/` 下形成一手资料复核，事实、推断与本项目建议分开记录。
- [x] `learning/ragent-column/PHASE-4-STEP-A-CHECKPOINT.md` 汇总术语、指标边界、冲突与待裁决问题。
- [x] 对照 Phase 3 递延项，列明哪些进入 Phase 4、哪些继续不在范围。
- [x] 文档链接与格式检查通过，并由项目所有者确认 Step A 后才进入 Step B。

## 6. Phase 4 先行护栏

- 不用单一“RAG 总分”替代意图、检索、生成、性能和费用的分项事实。
- 不把假 Provider 的协议测试当作答案质量证据，也不把一次真实模型调用当作统计结论。
- 默认 CI 不执行无界付费评测；真实调用必须显式守门、固定样本上限并记录实际模型身份。
- 评测数据不得保存未授权的用户问题、文档正文、工具结果或模型答案。
- 不先选框架再反推需求；指标与证据口径先于 RAGAS 或其他工具选型。

## 7. Validation

- `node scripts/check-doc-links.mjs`
- `git diff --check -- docs/exec-plans/active/2026-08-12-rag-evaluation-phase-plan.md`
- 人工核对：Step A 没有提前产出 B/C/D 内容；七个必须回答的问题均在检查点有明确落点。

## 8. Decision Log

- `2026-08-12`：项目所有者确认关闭 Phase 3 并授权进入 Phase 4 Step A。当前只启动 06-evaluation
  的学习整理、一手资料复核与检查点，不提前选择 RAGAS、不创建实现工单。
- `2026-08-12`：11 篇学习稿、一手资料复核与 Step A 检查点已形成，进入项目所有者评审。检查点建议
  第一版先做报告/API 而非专用页面，RAGAS 保持候选并须用中文人工对齐集验证；尚未进入 Step B。
- `2026-08-13`：项目所有者确认 Step A 七条原则、递延项归属与 D-01～D-05。Step A 关闭并进入
  Step B；第一版不做专用评测页面，所以 Step B 只产出 PRD，不产出 ui-spec 或可点击原型。
- `2026-08-13`：[Phase 4 PRD](../../prd/2026-08-13-rag-evaluation-prd.md)草案完成，落实受控能力夹具、
  三层评估集、同源 turn 录制、三种运行模式、分层指标、费用守门、报告/API/CLI 和人工复核；当前等待
  项目所有者确认 P-02～P-07，尚未进入 Step C。
- `2026-08-13`：项目所有者确认 P-02～P-07，PRD 转为 approved；无专用页面、不做 slides、三层
  数据集、Judge 校准门禁、正文保留和双 run A/B 边界冻结，进入 Step C 技术方案。
