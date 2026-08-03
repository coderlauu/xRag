# 19 — 诊断保留与分批清理

**What to build:** 实现按 7 天和最近 10,000 次逻辑调用的有界物理清理，并独立保留 breaker transition。

**Blocked by:** 无（02、04 已完成）

**Status:** ready

## Slice

- 每小时执行保留任务；每个删除事务最多处理 1,000 条 logical call。
- 删除 logical call 时 cascade candidate、route event 与 attempt；Embedding 贡献不建独立表。
- 10,000 条上限按稳定 `started_at,id` 顺序清理，不误删新记录。
- breaker transition 独立保留 7 天，不对 logical call 建 FK。
- 不修改 Phase 1 业务表，不实现隐私扫描或故障注入。

## Acceptance

- [ ] DIA-13~DIA-16 自动化。
- [ ] 10,001 条 7 天内数据只保留最新 10,000 条。
- [ ] 一次需删除超过 1,000 条时确实分成多个短事务。
- [ ] route event 与 attempt 随父调用级联删除，清理后不会残留可派生的 Embedding 贡献事实。
- [ ] `./mvnw -B verify` 通过。

**Design:** [data model §7](../../../tech/model-scheduling/data-model.md#7-保留与清理)
