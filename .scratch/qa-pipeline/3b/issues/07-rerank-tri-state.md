# 07 — 精排调用与三态传导

**What to build:** 调用 `RerankClient` 并把三态原样传导到结果对象；用假 Rerank 即可完成。

**Blocked by:** 06
**Status:** blocked

## Slice

- 真实精排 / Noop 降级 / 调用异常三条路径在结果上可区分。
- 降级时不回填任何分数；不用原始候选补齐 topN。
- 用稳定 ID 对齐，不用下标。
- 精排失败降级继续，不使本轮失败。

## Acceptance

- [ ] 3B-RANK-01~07 自动化通过。
- [ ] `3B-RANK-08`（真实链路）保持 `P`，依赖 Phase 2 工单 23，**不伪造 passed**。
- [ ] 返回不足 topN 时条数就是实际条数，无补齐代码路径。
- [ ] 输入被过滤或重排后结果不错位。
