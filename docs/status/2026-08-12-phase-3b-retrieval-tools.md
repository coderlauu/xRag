# 里程碑状态：Phase 3B 检索 + 工具

- `phase`: Phase 3 AI 知识问答核心链路 / 3B 子里程碑
- `status`: **closed**（`2026-08-12`）
- `plan`: [Phase 3 执行计划](../exec-plans/active/2026-08-07-qa-pipeline-phase-plan.md)
- `tickets`: [3B 工单目录](../../.scratch/qa-pipeline/3b/README.md)（17 张全部 complete）
- `matrix`: [测试矩阵](../../tech/qa-pipeline/test-matrix.md) §18~§26（76 条全部 passed）
- `retro`: [3B 复盘](../retro/2026-08-12-phase-3b-retrospective.md)

## 1. 交付范围

三通道混合检索（定向向量 / 全局向量 / 关键词）、RRF 融合与两段截断、精排三态、按目标的
部分失败与重试、工具白名单与副作用确认、以及它们的 API 契约与界面表达。

不含答案生成（属 3C）——3B 结束时系统仍然不产出答案，只产出**将来交给生成阶段的依据**。

| 组成 | 落点 |
|---|---|
| 检索编排 | `com.app.qa.retrieval`（通道、融合、精排、编排、重放合并） |
| 关键词索引 | `com.app.knowledge.keyword`（切词器、索引写入点、存量回填） |
| 跨模块端口 | `ChunkSearchPort` / `ChunkTextLookup` / `ChunkDisplayLookup`（均由知识库侧提供） |
| 工具 | `com.app.qa.tool`（白名单、Schema 复核、状态机、副作用确认） |
| API | 契约 §9~§12，turn 响应新增 `retrieval` / `tools` 两块 |
| 界面 | `/chat` 的来源区、降级表达、工具区与确认卡 |
| 数据 | V9 五张表（`document_chunk_keyword` + 四张 `qa_*`） |

## 2. 已验证事实

| 范围 | 证据 |
|---|---|
| 后端 | `./mvnw -B verify` **638 项，0 失败 0 错误**（真实 API 用例无 Key 自动跳过） |
| 前端 | `pnpm build` / `lint` / `test` 全通过，**69 项** |
| 整体门禁 | `scripts/ci-validate.sh` exit=0；CI 的 `validate` job 调用同一脚本 |
| 自动化用例 | 71 条 `A` 全部有测试方法落点并通过（矩阵 §26.1~§26.2） |
| 手工走查 | 8 条 `3B-UI-*` 在 1440/1024/720/375 四档走查通过（矩阵 §26.4）。**执行人是 AI 而非人工目视**，见 §3 |
| 真实供应商 | `3B-RANK-08` 一次真实 DashScope Rerank 调用：`SUCCEEDED` / `rerank_applied=t` / `http_status=200` / 请求 ID 脱敏生效（矩阵 §26.5） |
| 隐私边界 | 标记串扫描 3B 五张表全部文本列、3B 端点响应与 `ai_*` 诊断表，含反向断言（`Qa3bPrivacyAuditIntegrationTests`） |
| 无 Key 行为 | 应用照常启动；检索层退化为关键词召回、精排降级为未精排；3A 先短路成 `NO_MATCH` 因此端到端观察不到检索（`QaNoApiKeyBehaviourTests`） |
| 计费守门 | `check-deploy-contract.mjs` 强制 `Real*ApiTests` 必须带 `@EnabledIfEnvironmentVariable`，反证过 |

## 3. 证据边界

- **8 条 UI 走查由 AI 用浏览器工具驱动，不是人工目视。** 证据是 DOM 断言与截图，不是人的观感。
  像"`SKIPPED` 的措辞会不会被误读成失败"这类判断本来就该项目所有者亲自看一眼，本轮记录**不
  替代**那一步。可复现资产在 [walkthrough/](../../.scratch/qa-pipeline/3b/walkthrough/README.md)。
- 走查数据用 seed 而非真实链路：走真实链路要 3A 的意图评分（真实 Chat 调用、按次计费），
  且无密钥时 3A 会短路、检索压根不执行。

## 4. 期间做出的产品裁决

| 裁决 | 内容 |
|---|---|
| 工具执行失败 | 200 + `tools[].status=FAILED` + `failureCode`，不返回 4xx/5xx（契约 §12.1） |
| 检索全失败 | 200 + `retrieval.status=RETRIEVAL_FAILED`，不返回 502（契约 §12.2） |
| 有来源即 `RETRIEVED` | 不看目标状态；`partial` 扩为表达"结果不完整"（契约 §9.1） |

前两条的共同理由：**"这一次没成"是结果的一部分，不是请求失败**。用 5xx 表达会把整个 turn
的其余内容（来源、其他工具、理解结果）一起吞掉。第三条源自无 Key 部署的常态——向量通道
全失败而关键词兜住结果时，对着一屏有内容的结果说"没查成"比少说一句更糟。

## 5. 递延项

| 项 | 状态 | 归属 |
|---|---|---|
| 工具区显示内部名、确认卡参数表为空 | 已知 | 运行时白名单为空，且**配不上**——主源码里没有任何 `ToolExecutor` 实现。第一个真实工具落地时要重走 `3B-UI-05` |
| 原文片段预览 | 待接口 | 正文按 `chunkId` 另取，`api.md` 尚无该端点；界面已留展开位并说明 |
| 工具端点响应外壳 | 待改 | 契约 §10 要求返回整个 turn，工单 10 的实现返回 `{tools}`；前端按现状只合并 tools 块 |
| 3B-E2E-02（来源与工具互不阻塞的时序） | 未实现 | 工具调用需 3A 的动作映射先能产出工具意图 |
| 375px 顶部导航文字竖排 | 待修 | 属 3A 布局，不在 `3B-UI-07` 的断言内 |
| 诊断页 `verifiedCapabilities` 列 | 待裁决 | API 已返回真实值，加一列属 UI 改动，要过 ui-spec + 原型闸门 |
| 32 个环境变量未在 deploy/README 登记 | 已知缺口 | `STORAGE_*`/`UPLOAD_*`/`KNOWLEDGE_*`/`AI_DIAGNOSTICS_*`；补齐后契约检查可放开到全前缀 |
| 测试上下文变体过多 | 已知 | 十余个变体各带一条 Hikari 池，实测连接峰值 88/100；测试期池已压到 6 |

## 6. 3C 准入条件

1. 3B 的契约（§9~§12）视为冻结基线，3C 的流式与取消**不得改写**已确认的 `retrieval`/`tools`
   形状，只能新增。
2. 3C Step B 若引入排队、流式、停止或新的失败状态，必须更新 ui-spec 与原型并经确认——
   双界面硬闸门对 3C 同样有效。
3. `RetrievalOrchestrator` 的同步调用会拉长 `POST /turns`；3C 引入可取消任务时要连同它一起
   改传输形态，而不是只把生成做成流式。

## 7. 冻结的契约

- [API 契约](../../tech/qa-pipeline/api.md) §9 响应扩展、§9.1 状态算法、§10~§11 端点、§12 错误码
- [架构](../../tech/qa-pipeline/architecture.md) §18~§22（编排、失败边界、关键词通道、可观测性）
- [ui-spec](../../tech/qa-pipeline/conversation/ui-spec.md) §13（3B 界面增量，`approved`）
- [ADR 0008](../adr/0008-hybrid-retrieval-and-rrf-fusion.md)（混合检索与 RRF，决策 2 已于实现期修正）
