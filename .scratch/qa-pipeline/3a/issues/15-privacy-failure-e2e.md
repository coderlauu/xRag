# 15 — 隐私审计、故障注入与跨 revision E2E

**What to build:** 用标记串和故障 seam 证明问答业务正文只在允许表/API，诊断/日志不泄漏；覆盖模型/DB/cache/revision 组合故障和关键端到端竞态。

**Blocked by:** 10, 11, 13, 14
**Status:** complete

## Slice

- 标记用户问题、摘要、Prompt、模型原始 JSON、secret、Provider body，扫描 stage/log/API/log capture。
- 注入摘要/改写/评分/灰区/终态持久化/缓存替换故障。
- 用户提问与树发布并发，断言一个 turn 只见一个 revision。
- 删除对话不改 AI 诊断；诊断 writer 故障不改变业务终态。

## Acceptance

- [x] 3A-API-11~12、3A-E2E-03~07 自动化通过。
- [x] 审计自身可信：人为种入禁区的 marker 必须被扫描发现，报告不复制原文。
- [x] 所有 best-effort/降级路径有稳定 reason code，不吞持久化失败。
- [x] 测试 schema/资源完全清理，可重复并行运行且不污染共享数据库。

**Design:** [architecture §12, §14](../../../../tech/qa-pipeline/architecture.md)

## Evidence（2026-08-08）

`com.app.qa` 全集 92 项通过（本工单新增 6 项）；后端全量 `./mvnw -B verify` 506 项，唯一失败是既有的
知识库异步串扰 `IngestionIntegrationTests.Embedding失败时文档标记失败且没有留下分块`，单独复跑该类
14/14 通过——与 3A 无关，未改知识库业务代码。收费 API 测试因无 `DASHSCOPE_API_KEY` 被跳过（5 skipped）。

| 矩阵项 | 落点 |
|---|---|
| 3A-API-11 | `QaPrivacyAuditIntegrationTests` 七类标记串扫 `qa_turn_stage` 全部文本列 + 六个 `/api/qa` 端点 |
| 3A-API-12 | 同上，root logger `ListAppender` 捕获全量日志，七类正文一个都不许出现 |
| 3A-E2E-03 | `QuestionUnderstandingE2ETests`（工单 10 已落地，本次复核仍通过） |
| 3A-E2E-04 | `QaResilienceE2ETests` 在改写阶段真实发布下一个 revision，断言 turn 与全部 match 只挂旧版本，下一轮才切新版本 |
| 3A-E2E-05 | `QuestionUnderstandingE2ETests`（工单 10 已落地） |
| 3A-E2E-06 | `QaResilienceE2ETests` 两半：删除对话后 `ai_logical_call` 与 `qa_turn_stage` 原样保留；stage sink 抛异常时本轮仍为 `ACTIONS_READY` |
| 3A-E2E-07 | 全量 verify 的 506 项即证据（见上） |

**审计的允许范围是分类的，不是"全都不许出现"**：用户问题正文理应留在 `/messages` 与 Inspector 的规范化
问题里，意图 `description`/`promptSnippet` 理应留在意图树 API 里。写成一刀切会得到一个永远绿的假审计，
因此每类标记串各有自己的禁区表，并另有一例正向断言"正文确实还在它该在的地方"。

### 本工单发现并修复的缺陷

**阶段诊断失败会把已算完的 turn 判成 `FAILED`。** 四个编排组件都直接调 `TurnStageSink.record()`，而
`QuestionUnderstandingService.process()` 用一个 catch-all 兜住 `RuntimeException` 并写 `FAILED`——于是一次
stage 写失败就变成了业务失败。`JdbcTurnStageSink` 只吞 `DataAccessException`，别的实现不保证。

修复是把 best-effort 保证放到端口上：新增 `TurnStageSink.recordBestEffort()`，四个调用点全部改走它。
放在端口而不是各调用点，是因为漏掉任何一个的后果都一样严重。已同步进 architecture §14。
