# 07 — 精排调用与三态传导

**What to build:** 调用 `RerankClient` 并把三态原样传导到结果对象；用假 Rerank 即可完成。

**Blocked by:** 06
**Status:** complete

## Slice

- 真实精排 / Noop 降级 / 调用异常三条路径在结果上可区分。
- 降级时不回填任何分数；不用原始候选补齐 topN。
- 用稳定 ID 对齐，不用下标。
- 精排失败降级继续，不使本轮失败。

## Acceptance

- [x] 3B-RANK-01~07 自动化通过。
- [x] `3B-RANK-08`（真实链路）保持 `P`，依赖 Phase 2 工单 23，未伪造 passed。
- [x] 返回不足 topN 时条数就是实际条数，代码里没有补齐路径。
- [x] 用稳定 ID 对齐；构造"某条正文取不到因而未进请求"的用例证明不错位。

**Evidence:** `CandidateRerankerTests`（10 项，可编程假 Rerank）。

**多写了一条 ADR 没提的规则**：候选为空时**不调用精排、也不标记降级**。把"没东西可排"说成
"精排不可用"，界面上会出现一个毫无来由的"未精排"标记，而用户无从判断那是真的降级还是没结果。

**降级成因不进用户可见层**：无 Key / 供应商故障 / Noop 对用户是同一件事（ui-spec §13.4），
`RerankedCandidates.degradation` 只供处理详情与链路记录。异常只记类型不记 message——
供应商错误体不进日志也不进任何用户可见处。
