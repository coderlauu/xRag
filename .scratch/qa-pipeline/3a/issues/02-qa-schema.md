# 02 — 问答业务 DDL 与迁移约束

**What to build:** 用下一个可用 Flyway 版本新增 3A `qa_*` 表、索引与单行约束；只做 Schema/迁移测试，不写 Repository 或 Service。

**Blocked by:** —
**Status:** complete

## Slice

- 创建 conversation、turn、message、summary、subquestion、match、option、action、stage。
- 创建 intent head、revision、node snapshot。
- 落单活动 turn、当前 summary、sequence/revision、逻辑删除和闭集 check。
- migration 不修改 Phase 1/2 表语义；KB FK 只引用现有稳定 ID。

## Acceptance

- [x] 3A-DB-01、02、03、06、08、12 自动化通过。
- [x] 空库升级和已有 V1~V6 升级都成功；重复 validate 无漂移。
- [x] DDL 没有 Prompt/response/provider error 字段，不建立 Redis/任务许可表。
- [x] 实现时 Flyway HEAD 为 V6，使用 `V7__qa_pipeline_phase_3a.sql`，未发现并行 migration。

**Evidence:** `QaSchemaMigrationTests` 在独立 PostgreSQL Schema 验证空库、V6 增量、重复迁移和约束；
`2026-08-07 cd backend && ./mvnw -q verify` 通过。

**Design:** [data model §2~8](../../../../tech/qa-pipeline/data-model.md)
