# 06 — RRF 融合、去重与两段截断

**What to build:** 确定性 RRF 融合、按 `chunk_id` 去重与两段截断；纯函数为主，不触外部依赖。

**Blocked by:** 05
**Status:** complete

## Slice

- `score(c) = Σ 1/(60 + rank_i(c))`，rank 从 1 起，只消费排名。
- 去重键 `chunk_id`；分数累加；来源归属取 `priority` 最小的通道。
- 第一段截断到 `rerank-input-size`，第二段到 `top-n`。
- 去重失败 = 本轮检索失败；不与精排共用容错策略。

## Acceptance

- [x] 3B-FUSE-01~07 自动化通过。
- [x] 给定排名输入，输出顺序确定且可复现。
- [x] 通道分数放大一万倍，输出逐字段不变（证明只吃排名）。
- [x] 源文件静态断言不含 `hashCode`。

**Evidence:** `ReciprocalRankFusionTests`（9 项）。

**补了一条 ADR 没写死的规则**：完全并列时按 `chunkId` 升序。没有稳定 tie-break，同一份输入
两次运行可能给出不同顺序，"来源顺序即相关性顺序"这个对用户的承诺就成了随机数。已有专门用例。

`ReciprocalRankFusion` 有两个构造器（配置 / 直接给 k），Spring 因此挑不出注入点。给公开那个
加了 `@Autowired`，包私有那个只留给测试——生产一律走配置，不制造第二个事实来源。
