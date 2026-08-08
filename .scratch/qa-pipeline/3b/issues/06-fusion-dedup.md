# 06 — RRF 融合、去重与两段截断

**What to build:** 确定性 RRF 融合、按 `chunk_id` 去重与两段截断；纯函数为主，不触外部依赖。

**Blocked by:** 05
**Status:** blocked

## Slice

- `score(c) = Σ 1/(60 + rank_i(c))`，rank 从 1 起，只消费排名。
- 去重键 `chunk_id`；分数累加；来源归属取 `priority` 最小的通道。
- 第一段截断到 `rerank-input-size`，第二段到 `top-n`。
- 去重失败 = 本轮检索失败；不与精排共用容错策略。

## Acceptance

- [ ] 3B-FUSE-01~07 自动化通过。
- [ ] 给定排名输入，输出顺序确定且可复现。
- [ ] 通道分数量纲变化不改变输出顺序（证明只吃排名）。
- [ ] 代码中不存在 `hashCode` 一类去重兜底路径（静态断言）。
