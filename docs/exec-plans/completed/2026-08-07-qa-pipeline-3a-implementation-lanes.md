# 分角色任务书：AI 知识问答核心链路（Phase 3A Step D~F）

## 1. Metadata

- `status`: completed / archived（2026-08-12；Phase 3 已关闭）
- `created`: 2026-08-07
- `owner`: liuqiang
- `parent`: [Phase 3 执行计划](2026-08-07-qa-pipeline-phase-plan.md)
- `inputs`: [PRD](../../prd/2026-08-07-qa-pipeline-prd.md)、[架构](../../../tech/qa-pipeline/architecture.md)、[数据模型](../../../tech/qa-pipeline/data-model.md)、[API](../../../tech/qa-pipeline/api.md)、[测试矩阵](../../../tech/qa-pipeline/test-matrix.md)
- `adr`: [0006 采样绑定](../../adr/0006-bind-chat-sampling-to-service-tier.md)、[0007 版本化意图树](../../adr/0007-versioned-intent-tree.md)
- `tickets`: [.scratch/qa-pipeline/3a](../../../.scratch/qa-pipeline/3a/README.md)

## 2. Objective

交付 3A 可演示垂直切片：用户消息可靠持久化，系统按 8/12 水位压缩历史，改写为最多三个独立子问题，
固定一个版本化意图树快照并有界并行评分，最后稳定保存歧义引导或声明式检索动作。正式前端落地已确认的
问答 A — Conversation + Inspector 与意图树 C — Route Canvas，不伪造 3B 检索或 3C 答案。

## 3. Scope

### In Scope

- Step C 三件套、ADR 0007、API 和测试矩阵中全部 `3A-*`。
- 17 张可独立验收的原子工单，沿依赖前沿推进。
- PostgreSQL/Flyway、JdbcTemplate、同步 MVC、可编程假 Chat 和 React 两页实现。
- 自动化、双页面手工验收准备、CI 与非敏感部署配置登记。

### Out Of Scope

- 真实检索、Rerank、MCP、答案生成、SSE、停止、排队、限流。
- Prompt 热更新、意图回滚/导入导出、RBAC、多实例一致性。
- 真实 Provider 作为 3A 完成条件；收费 API 不进入 CI。

## 4. Lane 与工单

| # | 工单 | 产品 | 后端 | 前端 | 测试 | 运维 |
|---:|---|:---:|:---:|:---:|:---:|:---:|
| 01 | 3A 领域词汇、配置与 Prompt catalog |  | ● |  | ● |  |
| 02 | 问答业务 DDL 与迁移约束 |  | ● |  | ● |  |
| 03 | 对话/消息/本轮 Repository 与幂等 |  | ● |  | ● |  |
| 04 | 意图整树 revision Repository 与领域约束 |  | ● |  | ● |  |
| 05 | 对话生命周期 API 与中断恢复 | ● | ● |  | ● |  |
| 06 | 历史快照、摘要与水位线 |  | ● |  | ● |  |
| 07 | 查询归一化、改写与 JSON 容错 |  | ● |  | ● |  |
| 08 | 有界并行意图评分与全局封顶 |  | ● |  | ● |  |
| 09 | 歧义引导、选择闭环与动作映射 | ● | ● |  | ● |  |
| 10 | 3A 理解编排与稳定终态 |  | ● |  | ● |  |
| 11 | 意图树管理 API 与影响预览 | ● | ● |  | ● |  |
| 12 | 前端导航、API types 与共享状态基础 |  |  | ● | ● |  |
| 13 | 问答 A — Conversation + Inspector | ● |  | ● | ● |  |
| 14 | 意图树 C — Route Canvas | ● |  | ● | ● |  |
| 15 | 隐私审计、故障注入与跨 revision E2E |  | ● | ● | ● |  |
| 16 | 矩阵执行与双界面浏览器验收 | ● |  | ● | ● |  |
| 17 | CI、部署配置与 3A 门禁 |  | ● | ● | ● | ● |

## 5. Lanes

### Lane 产品

**写入范围**

- `docs/prd/2026-08-07-qa-pipeline-prd.md`
- `tech/qa-pipeline/{conversation,intent-tree}/ui-spec.md`
- `prototype/qa-pipeline/`（决策证据，不复制到正式前端）

**纪律与完成定义**

- 只实现已确认的问答 A、意图树 C；状态缺口先回规格/Decision Log。
- 3A 聊天区不出现假答案、假来源、假停止/排队状态。
- 歧义、无匹配、超过三主题、保存失败和 revision 冲突均有可恢复文案。
- [x] PRD 3A AC 可追溯到 API、实现和矩阵；两页浏览器演示经项目所有者确认（2026-08-08）。

### Lane 后端

**主要写入范围**

- `backend/src/main/java/com/app/qa/**`
- `backend/src/main/resources/prompts/qa/3a/**`
- 下一个 Flyway migration
- 与知识库的窄只读 lookup seam；不改 Phase 1 检索语义

**纪律与完成定义**

- Chat/数据库不放 Reactor；模型调用不在事务中。
- 所有树写走 expected revision + 完整新快照，没有可变单节点 SQL 旁路。
- 相同幂等 key 不重复创建消息或收费；终态 CAS 唯一。
- `qa_turn_stage` 和日志无正文；问答业务不依赖 Provider 内部包。
- [x] 从保存消息到 action/guidance 的垂直链路、恢复、API 与迁移完成。

### Lane 前端

**主要写入范围**

- `frontend/src/api/qa*.ts`
- `frontend/src/pages/ChatPage*`、`frontend/src/pages/chat/**`
- `frontend/src/pages/IntentTreePage*`、`frontend/src/pages/intent-tree/**`
- `frontend/src/App.tsx`、`components/AppLayout.tsx` 的必要路由/导航增量

**纪律与完成定义**

- 与原型共享信息架构，不复制 throwaway 原型代码或内嵌假 fixture 到生产路径。
- 服务端是 revision、父子校验和 effective enabled 的事实来源。
- 版本冲突保留编辑表单；危险操作先预览，实际命令仍带同 revision。
- [x] 1440/720/375px、键盘、焦点与非仅颜色状态满足两个 approved ui-spec。

### Lane 测试

**写入范围**

- `backend/src/test/java/com/app/qa/**`
- 相关 API/隐私/迁移集成测试
- `frontend/src/pages/**` 组件测试
- `tech/qa-pipeline/test-matrix.md` Step E 回写

**优先级**

1. 数据库唯一性、终态 CAS、revision 竞态和在途固定快照。
2. 摘要水位、改写/评分结构化输出的降级与 Prompt 字节预算。
3. 歧义边界、动作阈值和不调用 3B/3C 的负断言。
4. API 隐私扫描和两页面状态组合。

**完成定义**

- [x] 所有 `A` 项有自动化并通过（130 条）；`M` 项有实际日期/环境/证据（16 条，矩阵 §16）。

### Lane 运维

**写入范围**

- `scripts/ci-validate.sh`、`.github/workflows/ci.yml` 的必要门禁增量
- `deploy/README.md`、env example 的 3A 非敏感配置

**纪律与完成定义**

- CI 使用确定性假 Chat，不持有 `DASHSCOPE_API_KEY`。
- 登记阈值、8/12、Prompt 字节和 stale timeout；不把 Prompt 正文复制成环境变量。
- [x] backend verify、frontend build/lint/test、doc links 和整体 validate 纳入门禁。

## 6. 依赖前沿

**依赖前沿已走完：17 张工单全部 complete。** Step A~F 关闭，矩阵 146 条全部回写。
剩余闸门是 Step G 产物（[里程碑状态](../../status/2026-08-08-phase-3a-qa-understanding.md)、
[复盘](../../retro/2026-08-08-phase-3a-retrospective.md)）经项目所有者确认；确认后才进入 3B Step B。

```text
01 配置/Prompt ─┬─> 06 摘要 ───────────────┐
                ├─> 07 改写 ───────────────┤
                └─> 08 评分 <── 04 意图树 ─┤
02 业务 DDL ──> 03 对话 Repository ─> 05 生命周期 API ─┤
02 ───────────> 04 意图 revision ──────────┤
08 ───────────> 09 歧义/动作 ──────────────┤
03 + 05 + 06 + 07 + 08 + 09 ─────────────> 10 编排终态
04 ───────────────────────────────────────> 11 意图 API

12 前端基础 ─┬─> 13 问答 A（依赖 05,10）
             └─> 14 意图 C（依赖 11）
10 + 11 + 13 + 14 ───────────────────────> 15 隐私/E2E
01~15 ───────────────────────────────────> 16 矩阵/浏览器验收
01~16 ───────────────────────────────────> 17 CI/部署门禁
```

首批只允许 01、02、12 并行：它们写入范围不重叠。04 必须等 DDL 02；任何前端页面业务状态必须等对应 API，
但 12 可先落 types、route skeleton 和测试 harness。

## 7. 风险与护栏

| 风险 | 护栏 |
|---|---|
| 同步模型调用占满 Servlet 线程 | 3A 最多 3 子问题、有界专用池、模型 deadline；3C 再引入排队 |
| 整树写放大 | 当前树受 64 KiB 评分预算约束；压测 revision 写，不偷换可变表 |
| 模型 JSON“看起来能用”却越权 | 所有 ID/分数/数量在代码复核，unknown/NaN/重复有测试 |
| 前端画布自算一致性 | API 返回完整 snapshot/effective state，命令必须 expected revision |
| 幂等只挡 DB 写没挡收费调用 | 先创建/读取 turn，再由唯一获准的 PROCESSING owner 调模型 |
| 诊断泄漏正文 | 标记串扫描 API、stage、日志和 `ai_*`；字段白名单而非日志脱敏猜测 |

## 8. Validation

```bash
cd backend && ./mvnw -B verify
cd frontend && pnpm test --run && pnpm build && pnpm lint
node scripts/check-doc-links.mjs
./scripts/ci-validate.sh
```

外加 Step D 浏览器演示和 Step E 矩阵回写。3A 自动化不访问公网、不需要收费 Key。

## 9. Rollback

- 工单按依赖图小提交；未越过 API/Schema 契约的切片可逐张回退。
- 新 migration 只新增 `qa_*`。应用回退后保留业务表，不删除用户对话。
- 意图发布失败不推进 head；已经发布的 revision 不 update。业务级回滚通过以后创建新 revision 实现。
- 前端路由可单独撤回，不把原型设为生产 fallback。
- D-06 是 `com.app.ai` 独立前置，不因 3A 回退而回退；它是通用端口契约。

## 10. Decision Log

- `2026-08-07`: 采用用户批量确认的十项 Step C 默认值；工单拆分不得各自重解释。
- `2026-08-07`: 选 17 张原子工单；首批 01/02/12，后端以数据不变量和稳定终态为主干，双前端在 API 后汇合。
- `2026-08-07`: Step C 产物未确认前所有工单保持 blocked；提前决策授权不替代实际产物闸门。
- `2026-08-07`: 项目所有者一次性确认 Step C 全套产物；ADR 0007 与技术文档转为 approved，解锁首批 01/02/12，进入 Step D。
- `2026-08-07`: 03/04 完成 PostgreSQL 对话聚合与整树 revision seam；453 项后端测试通过，解锁 05/06/08/11。
- `2026-08-07`: 05 完成对话生命周期 API、ProblemDetail、不透明游标与中断恢复；进入 06 记忆摘要实现。
- `2026-08-07`: 06 完成 8/12 历史快照、增量摘要、水位 CAS 与失败降级；471 项后端测试通过，第三前沿剩余 08/11。
- `2026-08-07`: 08 完成固定 revision 的有界并行评分、严格输出复核、全局多样性封顶与无正文 stage；22 项聚焦回归通过，解锁 09。
- `2026-08-08`: 09 完成歧义三区间、灰区保守复核、固定引导、结构化选择闭环和声明式动作；后端全量 488 项通过，解锁 10。
- `2026-08-08`: 10 完成真实 3A 理解编排、稳定终态、Inspector 持久化恢复与跨阶段组合故障验证；后端全量 496 项通过，前沿推进 11。
- `2026-08-08`: 11 完成意图树完整快照、全部管理命令、影响预览、expected revision 冲突与 bindable KB API；QA 全集 86 项通过，解锁 13/14。
- `2026-08-08`: 16/17 完成，3A 全部工单关闭。130 条 `A` 项逐条核对落点后回写 passed（核对揪出 `3A-API-05` 跨归属 turn 404 无任何断言，已补）；16 条 `M` 项由项目所有者在 Chrome + 1440/720/375px、真实 DashScope Key 下走查全部 passed。矩阵 146 条无一留在 `P`。CI workflow 无需改动（`validate` job 直接调用 `ci-validate.sh`），`ci-validate.sh` 补上了一直缺失的 `pnpm lint`。Step G 产物（里程碑状态 + 复盘）已产出待确认。
- `2026-08-08`: 15 完成标记串隐私审计、故障注入与跨 revision E2E：七类正文各有自己的禁区表（一刀切会得到永远绿的假审计），扫 `qa_turn_stage` 全部文本列、六个 `/api/qa` 端点和全量日志，并有一例反向断言证明扫描确实会命中。发现并修复一个真实缺陷——阶段诊断写失败会被理解编排的 catch 当成理解失败，把已算完的 turn 判成 `FAILED`；best-effort 保证上移到 `TurnStageSink.recordBestEffort()`，四个调用点全部改走它，已同步 architecture §14。`com.app.qa` 92 项通过，后端全量 506 项仅剩既有知识库异步串扰（单独复跑 14/14 通过）。解锁 16。
- `2026-08-08`: 14 完成意图树 C — Route Canvas：完整快照画布（tree/treeitem 语义 + 缩放/滚动平移）、三种角色表单、KB 选择器与失效绑定阻塞、dirty guard、先 impact 后命令、两种 enable scope、revision 冲突保留表单并刷新当前版本供比较。成功后一律用服务端返回的整套 `nodes` 原子替换，不做本地单节点 patch。前端 51 项通过。画布不做拖拽换父：那会废掉键盘树语义，且 3A 本就不支持换父。解锁 15。
- `2026-08-08`: 13 完成问答 A 正式页面——对话增删改查与双游标翻页、幂等签名重试、三档响应式（常驻/抽屉/覆盖层）与 Esc 焦点返回、四种稳定终态与歧义单选闭环；前端 36 项通过。组件测试统一在全局 `fetch` 层 mock（沿用 CLAUDE.md 的既定纪律），并给 jsdom 补了 `<dialog>.showModal` 的最小实现——那是环境缺口，不改生产代码去迁就它。前沿推进 14。
