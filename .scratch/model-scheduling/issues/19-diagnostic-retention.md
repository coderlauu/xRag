# 19 — 诊断保留与分批清理

**What to build:** 实现按 7 天和最近 10,000 次逻辑调用的有界物理清理，并独立保留 breaker transition。

**Blocked by:** 无（02、04 已完成）

**Status:** done（2026-08-07）

## Slice

- 每小时执行保留任务；每个删除事务最多处理 1,000 条 logical call。
- 删除 logical call 时 cascade candidate、route event 与 attempt；Embedding 贡献不建独立表。
- 10,000 条上限按稳定 `started_at,id` 顺序清理，不误删新记录。
- breaker transition 独立保留 7 天，不对 logical call 建 FK。
- 不修改 Phase 1 业务表，不实现隐私扫描或故障注入。

## Acceptance

- [x] DIA-13：8 天前的调用被物理删除，candidate/route event/attempt 随 `on delete cascade` 一起消失，7 天内的调用及其子表完好。
- [x] DIA-14：10,001 条 7 天内数据只保留最新 10,000 条；按 `started_at desc, id desc` 稳定排序，被删的是最旧那条。
- [x] DIA-15：调用已按条数上限清理后，breaker transition 仍只按 7 天决定去留，不受上限影响。
- [x] DIA-16：2,500 条按时间清理确实分成 1000+1000+500 三次删除事务，不是一个大事务。
- [x] route event 与 attempt 随父调用级联删除，清理后不残留可派生的 Embedding 贡献事实。
- [x] `./scripts/ci-validate.sh` 通过（clean 构建后 backend 361 tests，0 failures，2 skipped）。
  最初记的是 372，同样受陈旧 surefire 报告影响虚高 11，详见工单 05 的同一条更正。

**Design:** [data model §7](../../../tech/model-scheduling/data-model.md#7-保留与清理)

## 实现记录（2026-08-07）

`com.app.ai.diagnostic.DiagnosticRetentionService`：`runOnce()` 依次跑三项清理（按时间删调用 →
按条数上限删调用 → 按时间删 transition），每项内部循环到本轮删不满一批为止，**每批一个独立短事务**。
返回 `RetentionResult(deletedCallsByAge, deletedCallsByCount, deletedTransitions, passes)`，
`passes` 是发出的删除事务总数，只用于日志与 DIA-16 断言，不参与控制流。

- **先按时间删再按条数删。** 顺序不能反：时间清理过后剩下的才是"最近 N 次"的真实分母，
  反过来会拿一批注定要删的过期记录去占 10,000 的名额。
- **cutoff 用注入的 `Clock` 算，不用 SQL 里的 `now()`。** data-model §7 的示例写的是
  `now() - interval '7 days'`，但那样测试没法控制时间；改成参数化的 `started_at < ?`，
  与仓库其余部分统一由 Clock 决定时间。
- **新增 `AiSchedulingConfig`。** `@EnableScheduling` 原先只在
  `knowledge.ingestion.IngestionSchedulingConfig` 上，而那个文件的注释明说"将来若要关掉只动这一个
  文件"——保留清理不该因为另一个模块关掉调度就静默停摆。重复 `@EnableScheduling` 是幂等的。
- **新增 `app.ai.diagnostics.retention.enabled`（默认 true）与 `.interval`（默认 1h）。**
  `@Scheduled` 在每个 Spring 上下文里都会跑，集成测试共用同一个库（ADR 0002 单实例假设），
  `ApplicationTests` 已用它关掉，沿用 `app.knowledge.ingestion.enabled` 建立的做法。
  `max-age-days` / `max-logical-calls` 直接复用工单 05 引入的键，没有另写默认值。

批量造数（DIA-14 要 10,001 条）走单条 `insert ... select from generate_series`，逐条走 writer
会把用例拖成分钟级；本组用例验的是删除口径而非写入语义。DIA-13 仍走真实 `JdbcDiagnosticStore`，
因为它要断言的正是 cascade。
