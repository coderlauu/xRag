# 05 — `ChunkSearchPort` 与三个通道

**What to build:** 知识库侧的窄查询端口与三个 `SearchChannel` 实现；不做融合与精排。

**Blocked by:** 01
**Status:** blocked

## Slice

- `ChunkSearchPort` 暴露定向向量、全局向量、关键词三个只读查询，由知识库模块实现。
- `IntentTargetedChannel` / `GlobalVectorChannel` / `KeywordChannel`，各有唯一 `priority`。
- 激活条件由 3A 动作决定；两个向量通道不同时激活。
- 检索用改写后的问题，不用用户原话。

## Acceptance

- [ ] 3B-CH-01~07 自动化通过。
- [ ] 定向激活时全局通道**零 SQL**（用可观测端口断言调用次数）。
- [ ] 通道只有一套 `priority`，代码中不存在第二套按类型的优先级。
- [ ] `com.app.qa` 不出现 chunk 表的 SQL 字符串（静态断言）。
- [ ] 不实现 `isEnabled(context)` 恒 true 的占位扩展点。
