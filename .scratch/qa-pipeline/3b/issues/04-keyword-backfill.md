# 04 — 关键词存量回填批处理

**What to build:** 带 `enabled` 开关的幂等回填组件，补齐 V9 之前已存在分块的关键词行。

**Blocked by:** 03
**Status:** complete

## Slice

- 按 `chunk_id` 游标分批，只处理向量表有而关键词表没有的行。
- 默认关闭；开启方式与运行方式登记部署 README。
- 幂等：重复执行不改变行数，只补缺失。
- 未回填完成时关键词通道照常工作，只是召回不全——这是已知降级，不是故障。

## Acceptance

- [x] 3B-KW-07、3B-KW-08 自动化通过。
- [x] 组件带 `enabled` 开关，默认 `false`（ADR 0002 单实例假设）。
- [x] 幂等：只补"向量表有、关键词表没有"的行；`batch-size=2` 跨三批补五条缺口，不跳过不重复。
- [x] [deploy/README](../../../deploy/README.md) 新增「检索与工具（Phase 3B）运维须知」，
      含开关、六个配置项与"换词典 = 重建 `document_chunk_keyword` 全表"。

**Evidence:** `KeywordBackfillIntegrationTests`（4 项）。

**多加了一条用例**：回填产出的 `tsv` 必须与正常写入路径逐字节相同。两条路径都走
`ChunkKeywordRepository.indexAll`，所以本该相同——但如果哪天有人给回填单独写一份切词逻辑，
症状会是"什么时候入的库决定了能不能被搜到"，那是最难查的一类问题。
